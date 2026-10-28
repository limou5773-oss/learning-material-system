package com.learningmaterial.system.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.dto.AiTaskDtos.CandidateResult;
import com.learningmaterial.system.dto.PersonalMaterialMatch;
import com.learningmaterial.system.dto.PreparedTaskSource;
import com.learningmaterial.system.dto.TaskLease;
import com.learningmaterial.system.entity.AiTaskRow;
import com.learningmaterial.system.mapper.AiTaskMapper;
import com.learningmaterial.system.service.ModelGenerationService;
import com.learningmaterial.system.service.PersonalMaterialSearchService;
import com.learningmaterial.system.service.TaskCandidateService;
import com.learningmaterial.system.service.TaskLeaseService;
import com.learningmaterial.system.service.TaskSourcePreparationService;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lms.task.processor.mode", havingValue = "agent")
public class AgentTaskProcessor implements TaskProcessor {
    private static final Set<String> RECOVERABLE =
            Set.of(
                    "SOURCE_TEXT_NOT_AVAILABLE",
                    "PDF_OCR_REQUIRED",
                    "PDF_READ_FAILED",
                    "OCR_EMPTY_RESULT",
                    "OCR_TIMEOUT",
                    "OCR_CONNECTION_FAILED",
                    "OCR_INVALID_RESPONSE",
                    "OCR_RATE_LIMITED",
                    "OCR_PROVIDER_UNAVAILABLE");
    private final AiTaskMapper tasks;
    private final TaskLeaseService leases;
    private final TaskSourcePreparationService preparation;
    private final ModelGenerationService models;
    private final TaskCandidateService candidates;
    private final PersonalMaterialSearchService search;
    private final ObjectMapper json;

    public AgentTaskProcessor(
            AiTaskMapper tasks,
            TaskLeaseService leases,
            TaskSourcePreparationService preparation,
            ModelGenerationService models,
            TaskCandidateService candidates,
            PersonalMaterialSearchService search,
            ObjectMapper json) {
        this.tasks = tasks;
        this.leases = leases;
        this.preparation = preparation;
        this.models = models;
        this.candidates = candidates;
        this.search = search;
        this.json = json;
    }

    private static class State {
        PreparedTaskSource prepared;
        CandidateResult candidate;
        List<PersonalMaterialMatch> matches = List.of();
        final List<Map<String, Object>> feedback = new ArrayList<>();
        boolean extracted, recognized, searched, generated;
    }

    @Override
    public void execute(TaskLease lease) {
        AiTaskRow task = tasks.findById(lease.taskId());
        if (task == null || !models.check(lease, task)) return;
        JsonNode config;
        try {
            config = json.readTree(task.getConfigJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new TaskExecutionException("INVALID_TASK_CONFIG");
        }
        State state = new State();
        while (models.check(lease, task)) {
            Long call = leases.startStep(lease, "planner", "MODEL");
            if (call == null) return;
            AgentPlanProtocol.Plan plan;
            try {
                plan = models.plan(lease, task, plannerInput(task, config, state), state.matches);
                if (plan == null) return;
                if (!leases.completeStep(
                        lease,
                        call,
                        true,
                        plan.stopReason() == null
                                ? "规划选择" + plan.actions().size() + "个受限动作"
                                : "明确停止：" + plan.stopReason(),
                        null)) return;
            } catch (TaskExecutionException ex) {
                leases.completeStep(lease, call, false, "规划未完成", ex.errorCode());
                throw ex;
            }
            if (plan.stopReason() != null)
                throw new TaskExecutionException("AGENT_STOP_" + plan.stopReason());
            for (int i = 0; i < plan.actions().size() - 1; i++)
                if (plan.actions().get(i).tool().equals("validate_candidate"))
                    throw new TaskExecutionException("AGENT_ACTION_NOT_ALLOWED");
            for (var action : plan.actions()) {
                if (!models.check(lease, task)) return;
                if (action.tool().equals("search_personal_materials")
                        && !config.path("allowRelatedSearch").asBoolean())
                    throw new TaskExecutionException("AGENT_SEARCH_NOT_ALLOWED");
                if (!allowed(config, state).contains(action.tool()))
                    throw new TaskExecutionException("AGENT_ACTION_NOT_ALLOWED");
                String kind =
                        action.tool().equals("generate_summary_cards")
                                ? "MODEL"
                                : action.tool().equals("recognize_image") ? "OCR" : "LOCAL";
                Long toolCall = leases.startStep(lease, action.tool(), kind);
                if (toolCall == null) return;
                try {
                    String summary;
                    switch (action.tool()) {
                        case "prepare_source", "extract_pdf_text", "recognize_image" -> {
                            if (action.tool().equals("recognize_image")) state.recognized = true;
                            else state.extracted = true;
                            state.prepared =
                                    preparation.prepareAgent(
                                            lease, config, action.tool(), state.prepared);
                            if (state.prepared == null) return;
                            summary = sourceSummary(state.prepared);
                            if (state.prepared.sources().isEmpty())
                                throw new TaskExecutionException(
                                        action.tool().equals("extract_pdf_text")
                                                ? "PDF_OCR_REQUIRED"
                                                : "SOURCE_TEXT_NOT_AVAILABLE");
                        }
                        case "search_personal_materials" -> {
                            state.searched = true;
                            var args = action.arguments();
                            state.matches =
                                    search.search(
                                            task.getUserId(),
                                            args.path("keywords").textValue(),
                                            args.path("subject").isNull()
                                                    ? null
                                                    : args.path("subject").textValue(),
                                            args.path("limit").intValue());
                            summary = "检索本人可访问的已保存知识卡片：" + state.matches.size() + "条；仅作关联上下文";
                        }
                        case "generate_summary_cards" -> {
                            state.generated = true;
                            models.generateAgent(
                                    lease,
                                    task,
                                    state.prepared,
                                    state.matches,
                                    result -> state.candidate = result);
                            if (state.candidate == null) return;
                            summary =
                                    "暂存"
                                            + state.candidate.cards().size()
                                            + "张模型候选卡片；等待validate_candidate校验";
                        }
                        case "validate_candidate" -> {
                            boolean published =
                                    models.publishAgent(
                                            lease,
                                            task,
                                            state.matches,
                                            () -> {
                                                if (!leases.completeStep(
                                                                lease,
                                                                toolCall,
                                                                true,
                                                                "已核验结构、主资料引用和关联目标；等待人工确认",
                                                                null)
                                                        || !candidates.finish(
                                                                lease, state.candidate))
                                                    throw new TaskExecutionException("LEASE_LOST");
                                            });
                            if (!published) return;
                            return;
                        }
                        default -> throw new TaskExecutionException("AGENT_PLAN_INVALID");
                    }
                    if (!leases.completeStep(lease, toolCall, true, summary, null)) return;
                    state.feedback.add(
                            Map.of("tool", action.tool(), "status", "SUCCESS", "summary", summary));
                } catch (TaskExecutionException ex) {
                    if (!leases.completeStep(
                            lease, toolCall, false, "动作未完成：" + ex.errorCode(), ex.errorCode()))
                        return;
                    if (!RECOVERABLE.contains(ex.errorCode())) throw ex;
                    var feedback = new LinkedHashMap<String, Object>();
                    feedback.put("tool", action.tool());
                    feedback.put("status", "FAILED");
                    feedback.put("errorCode", ex.errorCode());
                    if (state.prepared != null)
                        feedback.put("sourceState", sourceSummary(state.prepared));
                    state.feedback.add(feedback);
                    break;
                }
            }
        }
    }

    private List<String> allowed(JsonNode config, State state) {
        if (state.candidate != null) return List.of("validate_candidate");
        String type = config.path("inputSnapshot").path("type").asText();
        List<String> result = new ArrayList<>();
        if (!state.extracted && "NOTE".equals(type)) result.add("prepare_source");
        if (!state.extracted && "PDF".equals(type)) result.add("extract_pdf_text");
        if (!state.recognized
                && ("IMAGE".equals(type)
                        || "PDF".equals(type)
                                && state.prepared != null
                                && state.prepared.extraction() != null
                                && !state.prepared.extraction().needsOcrPages().isEmpty()))
            result.add("recognize_image");
        if (!state.searched && !state.generated && config.path("allowRelatedSearch").asBoolean())
            result.add("search_personal_materials");
        if (!state.generated && state.prepared != null && !state.prepared.sources().isEmpty())
            result.add("generate_summary_cards");
        return result;
    }

    private String plannerInput(AiTaskRow task, JsonNode config, State state) {
        var input = json.createObjectNode();
        input.put("goalType", task.getGoalType());
        input.put("instruction", task.getInstruction());
        input.put("sourceType", config.path("inputSnapshot").path("type").asText());
        input.set("selectedRange", config.path("pageRange"));
        var currentTools = allowed(config, state);
        input.set("allowedTools", json.valueToTree(currentTools));
        var batchTools = new ArrayList<>(currentTools);
        if (!state.generated
                && (currentTools.contains("prepare_source")
                        || currentTools.contains("extract_pdf_text")
                        || currentTools.contains("recognize_image")
                        || currentTools.contains("generate_summary_cards"))) {
            if (!batchTools.contains("generate_summary_cards"))
                batchTools.add("generate_summary_cards");
            batchTools.add("validate_candidate");
        }
        input.set("batchTools", json.valueToTree(batchTools));
        input.set("feedback", json.valueToTree(state.feedback));
        input.put("candidateStaged", state.candidate != null);
        input.put("relatedMatchCount", state.matches.size());
        if (state.prepared != null) {
            input.put("sourceState", sourceSummary(state.prepared));
            var snippets = input.putArray("sourceSnippets");
            for (var source : state.prepared.sources().stream().limit(3).toList())
                snippets.add(
                        source.getContent()
                                .substring(0, Math.min(800, source.getContent().length())));
        }
        return input.toString();
    }

    private static String sourceSummary(PreparedTaskSource prepared) {
        var extraction = prepared.extraction();
        return "主资料来源片段"
                + prepared.sources().size()
                + "个"
                + (extraction == null
                        ? ""
                        : "；已处理页"
                                + extraction.processedPages()
                                + "；需OCR页"
                                + extraction.needsOcrPages());
    }
}
