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
import com.learningmaterial.system.ocr.OcrSettings;
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
    private final PdfTextExtractionService pdfText;
    private final OcrExtractionService ocr;
    private final OcrSettings ocrSettings;

    public MockTaskProcessor(AiTaskMapper tasks, TaskLeaseService leases,
                             TaskCandidateService candidates, ObjectMapper json, PdfTextExtractionService pdfText, OcrExtractionService ocr, OcrSettings ocrSettings) {
        this.tasks = tasks;
        this.leases = leases;
        this.candidates = candidates;
        this.json = json;
        this.pdfText = pdfText;
        this.ocr = ocr; this.ocrSettings = ocrSettings;
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
        boolean pdf = "PDF".equals(config.path("inputSnapshot").path("type").asText());
        boolean imageOcr = ocrSettings.enabled() && "IMAGE".equals(config.path("inputSnapshot").path("type").asText());
        String prepareTool = pdf ? "extract_pdf_text" : imageOcr ? "recognize_image" : "prepare_source";
        if (pdf) prepareArgs.set("pageRange", config.path("pageRange"));
        MockToolProtocol.validate(prepareTool, prepareArgs, task);
        Long prepareCall = leases.startStep(lease, prepareTool, imageOcr ? "OCR" : "LOCAL");
        if (prepareCall == null) return;
        List<MaterialChunkRow> sources;
        PdfTextExtractionService.Extraction extraction = null;
        OcrExtractionService.Outcome recognized = null;
        try {
            if (pdf) {
                extraction = pdfText.extract(lease);
                if (extraction == null) return;
                sources = extraction.sources();
                if (sources.isEmpty() && !ocrSettings.enabled()) throw new TaskExecutionException("PDF_OCR_REQUIRED");
            } else if (imageOcr) {
                recognized = ocr.extract(lease, null);
                if (recognized == null) return;
                extraction = recognized.extraction(); sources = extraction.sources();
            } else sources = candidates.saveSources(lease, prepare(config.path("inputSnapshot")));
        } catch (TaskExecutionException ex) {
            String explanation = switch (ex.errorCode()) {
                case "PDF_OCR_REQUIRED" -> "所选页没有可提取文字，需要OCR或为空白页；未生成候选。";
                case "PDF_TEXT_LIMIT_EXCEEDED" -> "所选页文字超过处理上限，请缩小页段后重试；未截断文字。";
                default -> "来源文字提取未完成，请检查资料后重试。";
            };
            leases.completeStep(lease, prepareCall, false, imageOcr ? ocr.attemptSummary(lease) : explanation, ex.errorCode());
            throw ex;
        }
        if (sources == null || !leases.completeStep(lease, prepareCall, true,
            recognized != null ? recognized.summary() : sources.size() + (pdf ? "个PDF文字层来源片段" : "个可核验的模拟来源片段"), null)) return;
        if (pdf && ocrSettings.enabled() && extraction != null && !extraction.needsOcrPages().isEmpty()) {
            Long ocrCall = leases.startStep(lease, "recognize_image", "OCR");
            if (ocrCall == null) return;
            try {
                recognized = ocr.extract(lease, extraction);
                if (recognized == null) return;
                extraction = recognized.extraction(); sources = extraction.sources();
            } catch (TaskExecutionException ex) {
                leases.completeStep(lease, ocrCall, false, ocr.attemptSummary(lease), ex.errorCode()); throw ex;
            }
            if (!leases.completeStep(lease, ocrCall, true, recognized.summary(), null)) return;
        }

        ObjectNode generateArgs = json.createObjectNode().put("goalType", task.getGoalType());
        var ids = generateArgs.putArray("chunkIds");
        for (MaterialChunkRow row : sources) ids.add(row.getId().toString());
        MockToolProtocol.validate("generate_summary_cards", generateArgs, task);
        Long generateCall = leases.startStep(lease, "generate_summary_cards", "LOCAL");
        if (generateCall == null) return;
        CandidateResult result = generate(task, config.path("inputSnapshot"), sources,
            config.path("pageRange"), extraction);
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
        if ("IMAGE".equals(type)) {
            String title = snapshot.path("title").asText("").trim();
            if (title.isEmpty()) throw new TaskExecutionException("SOURCE_TEXT_NOT_AVAILABLE");
            return List.of(new SourceDraft(TaskCandidateService.MOCK_METADATA_CHUNK_NO,
                title.substring(0, Math.min(title.length(), 500)),
                "METADATA", "仅引用资料标题；尚未提取文件正文"));
        }
        throw new TaskExecutionException("UNSUPPORTED_SOURCE_TYPE");
    }

    private CandidateResult generate(AiTaskRow task, JsonNode snapshot,
                                     List<MaterialChunkRow> sources, JsonNode pageRange,
                                     PdfTextExtractionService.Extraction extraction) {
        boolean metadataOnly = "METADATA".equals(sources.getFirst().getExtractionMethod());
        String first = sources.stream().map(MaterialChunkRow::getContent).filter(content -> !content.isBlank())
            .findFirst().orElseThrow(() -> new TaskExecutionException("SOURCE_TEXT_NOT_AVAILABLE")).stripLeading();
        String summary = metadataOnly ? "模拟摘要：仅依据资料标题生成，尚未读取文件正文。"
            : "模拟摘要：" + first.substring(0, safeEnd(first, 0, 180));
        List<CandidateCard> cards = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            MaterialChunkRow source = sources.get(i);
            if (source.getContent().isBlank()) continue;
            int offset = ("PDF_TEXT".equals(source.getExtractionMethod()) || "OCR".equals(source.getExtractionMethod()))
                ? source.getContent().length() - source.getContent().stripLeading().length() : 0;
            int end = safeEnd(source.getContent(), offset, 160);
            String quote = source.getContent().substring(offset, end);
            SourceRef ref = new SourceRef(source.getId().toString(), quote, offset, end);
            String content = metadataOnly ? "待核对资料标题：" + quote + "。文件正文尚未解析。"
                : "待核对原文片段：" + quote;
            cards.add(new CandidateCard("mock-" + (i + 1), "模拟卡片 " + (i + 1), content,
                null, snapshot.path("subject").isNull() ? null : snapshot.path("subject").asText(null),
                List.of("模拟结果"), List.of(ref), null));
        }
        List<String> warnings = new ArrayList<>();
        warnings.add("模拟生成，仅供流程验证；请对照原资料核查，不能作为真实AI分析结果。");
        if (metadataOnly) warnings.add("文件正文尚未提取；候选仅引用资料标题，不能代表文件内容。");
        if (extraction != null) warnings.addAll(extraction.warnings());
        String note = snapshot.path("noteText").asText("").trim();
        if (!metadataOnly && note.length() > 1500) warnings.add("本次模拟仅处理文字笔记前1500个字符。");
        Integer totalPages = snapshot.path("pageCount").isIntegralNumber()
            ? snapshot.path("pageCount").intValue() : null;
        if (extraction != null && "PDF".equals(snapshot.path("type").asText())) totalPages = extraction.totalPages();
        if (!pageRange.isMissingNode() && !pageRange.isNull() && metadataOnly) {
            warnings.add("所选PDF页段尚未解析，实际处理页数为0。");
        }
        return new CandidateResult(task.getId().toString(), 1, task.getMaterialVersion(), "MOCK",
            summary, List.of("模拟结果"), cards, List.of(), totalPages,
            extraction == null ? List.of() : extraction.processedPages(), warnings);
    }

    private static int safeEnd(String text, int start, int length) {
        int end = Math.min(text.length(), start + length);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
            && Character.isLowSurrogate(text.charAt(end))) end--;
        return end;
    }
}
