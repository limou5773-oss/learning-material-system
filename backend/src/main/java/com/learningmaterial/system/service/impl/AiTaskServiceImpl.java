package com.learningmaterial.system.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.AiTaskDtos;
import com.learningmaterial.system.dto.AiTaskDtos.PageRange;
import com.learningmaterial.system.dto.AiTaskDtos.RetryInput;
import com.learningmaterial.system.dto.AiTaskDtos.TaskInput;
import com.learningmaterial.system.dto.AiTaskDtos.TaskStepView;
import com.learningmaterial.system.dto.AiTaskDtos.TaskView;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.entity.AiTaskRow;
import com.learningmaterial.system.entity.MaterialRow;
import com.learningmaterial.system.mapper.AiTaskMapper;
import com.learningmaterial.system.mapper.AiToolCallMapper;
import com.learningmaterial.system.mapper.MaterialMapper;
import com.learningmaterial.system.mapper.ModelAttemptMapper;
import com.learningmaterial.system.mapper.OcrAttemptMapper;
import com.learningmaterial.system.mapper.UserMapper;
import com.learningmaterial.system.service.AiTaskService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTaskServiceImpl implements AiTaskService {
    private static final List<String> STATUSES = List.of(
        "QUEUED", "RUNNING", "WAIT_CONFIRM", "SUCCEEDED", "FAILED", "CANCELLED");

    private final AiTaskMapper tasks;
    private final AiToolCallMapper calls;
    private final OcrAttemptMapper ocrAttempts;
    private final ModelAttemptMapper modelAttempts;
    private final MaterialMapper materials;
    private final UserMapper users;
    private final ObjectMapper json;
    private final Clock clock;
    private final com.learningmaterial.system.integration.model.ModelSettings modelSettings;
    @org.springframework.beans.factory.annotation.Value("${lms.task.processor.mode:disabled}")
    private String processorMode;

    public AiTaskServiceImpl(AiTaskMapper tasks, AiToolCallMapper calls, MaterialMapper materials, UserMapper users,
                         ObjectMapper json, Clock clock, OcrAttemptMapper ocrAttempts, ModelAttemptMapper modelAttempts, com.learningmaterial.system.integration.model.ModelSettings modelSettings) {
        this.tasks = tasks;
        this.calls = calls;
        this.ocrAttempts = ocrAttempts; this.modelAttempts = modelAttempts;
        this.materials = materials;
        this.users = users;
        this.json = json;
        this.clock = clock;
        this.modelSettings = modelSettings;
    }

    @Override
    @Transactional
    public TaskView submit(long userId, TaskInput input) {
        return submitInternal(userId, input, null);
    }

    private TaskView submitInternal(long userId, TaskInput input, Long parentTaskId) {
        long materialId = parseId(input.materialId());
        if (input.materialVersion() == null || input.materialVersion() < 1 || input.goalType() == null) {
            throw invalid("INVALID_TASK", "任务目标或资料版本无效");
        }
        String requestKey = input.requestKey() == null ? "" : input.requestKey().trim();
        if (requestKey.isEmpty() || requestKey.length() > 64 || requestKey.chars().anyMatch(Character::isISOControl)) {
            throw invalid("INVALID_REQUEST_KEY", "请求键格式无效");
        }
        String instruction = input.instruction() == null ? null : input.instruction().trim();
        if (instruction != null && instruction.isEmpty()) instruction = null;
        if (instruction != null && instruction.length() > 1000) {
            throw invalid("INVALID_INSTRUCTION", "补充说明不能超过1000字");
        }
        if (!Boolean.TRUE.equals(input.externalProcessingConsent())) {
            throw invalid("CONSENT_REQUIRED", "请先同意资料用于外部处理");
        }
        PageRange range = input.pageRange();
        if (range != null && (range.from() < 1 || range.to() < range.from() || range.to() - range.from() + 1 > 20)) {
            throw invalid("INVALID_PAGE_RANGE", "PDF页段必须有效且不超过20页");
        }
        NormalizedRequest normalized = new NormalizedRequest(materialId, input.materialVersion(),
            input.goalType().name(), instruction, range, input.allowRelatedSearch(), true);
        String requestHash = sha256(serialize(normalized)
            + (parentTaskId == null ? "" : "|parentTaskId=" + parentTaskId));

        if (users.lockById(userId) == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录");
        }
        AiTaskRow existing = tasks.findByRequest(userId, requestKey);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "REQUEST_KEY_CONFLICT", "请求键已用于不同输入");
            }
            return TaskView.from(existing);
        }

        MaterialRow material = materials.findOwnedForUpdate(materialId, userId);
        if (material == null) throw new ApiException(HttpStatus.NOT_FOUND, "MATERIAL_NOT_FOUND", "资料不存在");
        if (!"NORMAL".equals(material.getAccessState())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MATERIAL_RESTRICTED", "该资料暂不可提交整理");
        }
        if (!material.getVersion().equals(input.materialVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "MATERIAL_VERSION_CONFLICT", "资料版本已变化",
                material.getVersion());
        }
        if ("PDF".equals(material.getMaterialType())) {
            if (range == null || material.getPageCount() == null || range.to() > material.getPageCount()) {
                throw invalid("INVALID_PAGE_RANGE", "请选择PDF内有效的页段");
            }
        } else if (range != null) {
            throw invalid("INVALID_PAGE_RANGE", "文字和图片资料不使用PDF页段");
        }
        if (tasks.countActive(userId) >= 2) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TASK_LIMIT", "最多同时排队或执行2个任务");
        }

        Instant instant = clock.instant();
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        AiTaskRow row = new AiTaskRow();
        row.setUserId(userId);
        row.setMaterialId(materialId);
        row.setMaterialVersion(input.materialVersion());
        row.setRequestKey(requestKey);
        row.setRequestHash(requestHash);
        row.setParentTaskId(parentTaskId);
        row.setGoalType(input.goalType().name());
        row.setInstruction(instruction);
        row.setStatus("QUEUED");
        row.setStage("QUEUED");
        row.setStepCount(0);
        row.setCancelFlag(false);
        row.setExecutionEpoch(0);
        row.setConfigJson(configJson(material, range, input.allowRelatedSearch(), instant));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        tasks.insert(row);
        return TaskView.from(row);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskView get(long userId, String id) {
        AiTaskRow row = tasks.findOwned(userId, parseId(id));
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
        return TaskView.from(row);
    }

    @Override
    @Transactional
    public TaskView cancel(long userId, String id) {
        long taskId = parseId(id);
        AiTaskRow row = tasks.findOwnedForUpdate(userId, taskId);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
        if ("CANCELLED".equals(row.getStatus())) return TaskView.from(row);
        if ("FAILED".equals(row.getStatus()) || "SUCCEEDED".equals(row.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_ALREADY_FINISHED", "该任务已结束，不能取消");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        tasks.cancelOwned(taskId, userId, now);
        calls.markRunningUnknown(taskId, "TASK_CANCELLED", now);
        ocrAttempts.markRunningUnknown(taskId, "TASK_CANCELLED", now);
        modelAttempts.markRunningUnknown(taskId, "TASK_CANCELLED", now);
        return TaskView.from(tasks.findOwned(userId, taskId));
    }

    @Override
    @Transactional
    public TaskView retry(long userId, String id, RetryInput input) {
        AiTaskRow parent = tasks.findOwned(userId, parseId(id));
        if (parent == null) throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
        if (!List.of("FAILED", "CANCELLED").contains(parent.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_RETRYABLE", "仅失败或取消的任务可以重试");
        }
        JsonNode config;
        try { config = json.readTree(parent.getConfigJson()); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("任务配置无法读取", ex); }
        JsonNode rangeNode = config.path("pageRange");
        PageRange range = rangeNode.isObject()
            ? new PageRange(rangeNode.path("from").asInt(), rangeNode.path("to").asInt()) : null;
        TaskInput request = new TaskInput(parent.getMaterialId().toString(), input.materialVersion(),
            input.requestKey(), AiTaskDtos.GoalType.valueOf(parent.getGoalType()), parent.getInstruction(),
            range, config.path("allowRelatedSearch").asBoolean(false), input.externalProcessingConsent());
        return submitInternal(userId, request, parent.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<TaskStepView> steps(long userId, String id, int page, int pageSize) {
        long taskId = parseId(id);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw invalid("INVALID_PAGE", "分页参数无效");
        if (tasks.findOwned(userId, taskId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
        }
        long total = calls.countOwned(userId, taskId);
        return new PageView<>(calls.listOwned(userId, taskId, pageSize, (long) (page - 1) * pageSize)
            .stream().map(TaskStepView::from).toList(), page, pageSize, total);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<TaskView> list(long userId, String status, int page, int pageSize) {
        if (status != null && !STATUSES.contains(status)) throw invalid("INVALID_STATUS", "任务状态无效");
        if (page < 1 || pageSize < 1 || pageSize > 100) throw invalid("INVALID_PAGE", "分页参数无效");
        long total = tasks.countOwned(userId, status);
        List<TaskView> items = tasks.listOwned(userId, status, pageSize, (long) (page - 1) * pageSize)
            .stream().map(TaskView::from).toList();
        return new PageView<>(items, page, pageSize, total);
    }

    private String configJson(MaterialRow material, PageRange range, boolean allowRelatedSearch, Instant consentAt) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", material.getMaterialType());
        source.put("title", material.getTitle());
        source.put("materialVersion", material.getVersion());
        source.put("noteText", material.getNoteText());
        source.put("subject", material.getSubject());
        source.put("pageCount", material.getPageCount());
        source.put("fileHash", material.getFileHash());
        source.put("storageKey", material.getStorageKey());
        source.put("fileSize", material.getFileSize());
        source.put("mimeType", material.getMimeType());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("processorMode", processorMode);
        config.put("pageRange", range);
        config.put("allowRelatedSearch", allowRelatedSearch);
        config.put("externalProcessingConsentAt", consentAt.toString());
        config.put("inputSnapshot", source);
        config.put("modelConfig", Map.of("provider", modelSettings.provider(), "model", modelSettings.model(),
            "status", "disabled".equalsIgnoreCase(modelSettings.provider()) ? "NOT_CONFIGURED" : "SNAPSHOT"));
        config.put("budget", Map.of("maxSteps", 8, "maxModelAttempts", "agent".equals(processorMode) ? 8 : 2,
            "maxGenerationAttempts", 2, "maxPlannerAttempts", "agent".equals(processorMode) ? 4 : 0, "maxOcrPages", 20));
        return serialize(config);
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("任务配置序列化失败", ex); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256不可用", ex); }
    }

    private static long parseId(String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "ID格式错误");
        }
        try { return Long.parseLong(id); }
        catch (NumberFormatException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "ID格式错误"); }
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private record NormalizedRequest(long materialId, int materialVersion, String goalType,
                                     String instruction, PageRange pageRange,
                                     boolean allowRelatedSearch, boolean externalProcessingConsent) { }
}
