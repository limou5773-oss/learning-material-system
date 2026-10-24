package com.learningmaterial.system.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learningmaterial.system.task.AiTaskDtos.CandidateCard;
import com.learningmaterial.system.task.AiTaskDtos.CandidateResult;
import com.learningmaterial.system.task.AiTaskDtos.SourceRef;
import com.learningmaterial.system.task.TaskCandidateService.SourceDraft;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "lms.task.processor.mode", havingValue = "mock")
public class MockTaskProcessor implements TaskProcessor {
    private final AiTaskMapper tasks;
    private final TaskLeaseService leases;
    private final TaskCandidateService candidates;
    private final ObjectMapper json;

    public MockTaskProcessor(AiTaskMapper tasks, TaskLeaseService leases,
                             TaskCandidateService candidates, ObjectMapper json) {
        this.tasks = tasks;
        this.leases = leases;
        this.candidates = candidates;
        this.json = json;
    }

    @Override
    public void execute(TaskLeaseService.Lease lease) {
        AiTaskRow task = tasks.findById(lease.taskId());
        if (task == null || !leases.isCurrent(lease)) return;
        JsonNode config;
        try { config = json.readTree(task.getConfigJson()); }
        catch (JsonProcessingException ex) { throw new TaskExecutionException("INVALID_TASK_CONFIG"); }

        ObjectNode prepareArgs = json.createObjectNode()
            .put("materialId", task.getMaterialId().toString())
            .put("materialVersion", task.getMaterialVersion());
        MockToolProtocol.validate("prepare_source", prepareArgs, task);
        Long prepareCall = leases.startStep(lease, "prepare_source", "LOCAL");
        if (prepareCall == null) return;
        List<SourceDraft> drafts = prepare(config.path("inputSnapshot"));
        List<MaterialChunkRow> sources = candidates.saveSources(lease, drafts);
        if (sources == null || !leases.completeStep(lease, prepareCall, true,
            sources.size() + "个可核验的模拟来源片段", null)) return;

        ObjectNode generateArgs = json.createObjectNode().put("goalType", task.getGoalType());
        var ids = generateArgs.putArray("chunkIds");
        sources.forEach(row -> ids.add(row.getId().toString()));
        MockToolProtocol.validate("generate_summary_cards", generateArgs, task);
        Long generateCall = leases.startStep(lease, "generate_summary_cards", "LOCAL");
        if (generateCall == null) return;
        CandidateResult result = generate(task, config.path("inputSnapshot"), sources,
            config.path("pageRange"));
        if (!leases.completeStep(lease, generateCall, true,
            result.cards().size() + "张模拟候选卡片", null)) return;

        ObjectNode validateArgs = json.createObjectNode()
            .put("taskId", task.getId().toString())
            .put("resultVersion", 1)
            .put("sourceVersion", task.getMaterialVersion());
        MockToolProtocol.validate("validate_candidate", validateArgs, task);
        Long validateCall = leases.startStep(lease, "validate_candidate", "LOCAL");
        if (validateCall == null) return;
        if (!leases.completeStep(lease, validateCall, true, "来源归属与引用位置已核验", null)) return;
        candidates.finish(lease, result);
    }

    private List<SourceDraft> prepare(JsonNode snapshot) {
        String type = snapshot.path("type").asText("");
        if ("NOTE".equals(type)) {
            String note = snapshot.path("noteText").asText("").trim();
            if (note.isEmpty()) throw new TaskExecutionException("SOURCE_TEXT_NOT_AVAILABLE");
            List<SourceDraft> drafts = new ArrayList<>();
            for (int start = 0; start < note.length() && drafts.size() < 3; start += 500) {
                String part = note.substring(start, Math.min(start + 500, note.length()));
                drafts.add(new SourceDraft(drafts.size() + 1, part, "TEXT", "文字笔记原文片段"));
            }
            return drafts;
        }
        if ("PDF".equals(type) || "IMAGE".equals(type)) {
            String title = snapshot.path("title").asText("").trim();
            if (title.isEmpty()) throw new TaskExecutionException("SOURCE_TEXT_NOT_AVAILABLE");
            return List.of(new SourceDraft(TaskCandidateService.MOCK_METADATA_CHUNK_NO,
                title.substring(0, Math.min(title.length(), 500)),
                "METADATA", "仅引用资料标题；尚未提取文件正文"));
        }
        throw new TaskExecutionException("UNSUPPORTED_SOURCE_TYPE");
    }

    private CandidateResult generate(AiTaskRow task, JsonNode snapshot,
                                     List<MaterialChunkRow> sources, JsonNode pageRange) {
        boolean metadataOnly = "METADATA".equals(sources.getFirst().getExtractionMethod());
        String first = sources.getFirst().getContent();
        String summary = metadataOnly ? "模拟摘要：仅依据资料标题生成，尚未读取文件正文。"
            : "模拟摘要：" + first.substring(0, Math.min(first.length(), 180));
        List<CandidateCard> cards = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            MaterialChunkRow source = sources.get(i);
            String quote = source.getContent().substring(0, Math.min(source.getContent().length(), 160));
            SourceRef ref = new SourceRef(source.getId().toString(), quote, 0, quote.length());
            String content = metadataOnly ? "待核对资料标题：" + quote + "。文件正文尚未解析。"
                : "待核对原文片段：" + quote;
            cards.add(new CandidateCard("mock-" + (i + 1), "模拟卡片 " + (i + 1), content,
                null, snapshot.path("subject").isNull() ? null : snapshot.path("subject").asText(null),
                List.of("模拟结果"), List.of(ref), null));
        }
        List<String> warnings = new ArrayList<>();
        warnings.add("模拟生成，仅供流程验证；请对照原资料核查，不能作为真实AI分析结果。");
        if (metadataOnly) warnings.add("文件正文尚未提取；候选仅引用资料标题，不能代表文件内容。");
        String note = snapshot.path("noteText").asText("").trim();
        if (!metadataOnly && note.length() > 1500) warnings.add("本次模拟仅处理文字笔记前1500个字符。");
        Integer totalPages = snapshot.path("pageCount").isIntegralNumber()
            ? snapshot.path("pageCount").intValue() : null;
        if (!pageRange.isMissingNode() && !pageRange.isNull() && metadataOnly) {
            warnings.add("所选PDF页段尚未解析，实际处理页数为0。");
        }
        return new CandidateResult(task.getId().toString(), 1, task.getMaterialVersion(), "MOCK",
            summary, List.of("模拟结果"), cards, List.of(), totalPages, List.of(), warnings);
    }
}
