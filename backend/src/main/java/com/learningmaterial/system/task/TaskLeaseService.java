package com.learningmaterial.system.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

@Service
public class TaskLeaseService {
    public static final int LEASE_SECONDS = 30;
    public static final int EXECUTION_SECONDS = 300;
    private static final Set<String> TOOL_KINDS = Set.of("LOCAL", "MODEL", "OCR");
    private static final Set<String> TOOL_NAMES = Set.of("prepare_source", "extract_pdf_text", "recognize_image",
        "search_personal_materials", "generate_summary_cards", "validate_candidate", "planner");

    private final AiTaskMapper tasks;
    private final AiToolCallMapper calls;
    private final OcrAttemptMapper ocrAttempts;
    private final ModelAttemptMapper modelAttempts;
    private final Clock clock;
    private final ObjectMapper json;
    private final NotificationService notifications;

    public TaskLeaseService(AiTaskMapper tasks, AiToolCallMapper calls, Clock clock, ObjectMapper json, OcrAttemptMapper ocrAttempts, ModelAttemptMapper modelAttempts, NotificationService notifications) {
        this.notifications = notifications;
        this.tasks = tasks;
        this.calls = calls;
        this.ocrAttempts = ocrAttempts; this.modelAttempts = modelAttempts;
        this.clock = clock;
        this.json = json;
    }

    public record Lease(long taskId, int epoch) { }

    @Transactional
    public Lease claim() {
        LocalDateTime now = now();
        AiTaskRow row = tasks.findClaimable(now);
        if (row == null) return null;
        if (row.getStartedAt() != null && !row.getStartedAt().plusSeconds(EXECUTION_SECONDS).isAfter(now)) {
            if (tasks.failTimedOut(row.getId(), row.getExecutionEpoch(), now) == 1) {
                calls.markRunningUnknown(row.getId(), "TASK_TIMEOUT", now);
                ocrAttempts.markRunningUnknown(row.getId(), "TASK_TIMEOUT", now);
                modelAttempts.markRunningUnknown(row.getId(), "TASK_TIMEOUT", now);
                notifications.recordTaskState(row.getUserId(), row.getId(), "FAILED", now);
            }
            return null;
        }
        int nextEpoch = row.getExecutionEpoch() + 1;
        if (tasks.claim(row.getId(), row.getExecutionEpoch(), now, now.plusSeconds(LEASE_SECONDS)) != 1) {
            return null;
        }
        calls.markOlderUnknown(row.getId(), nextEpoch, now);
        ocrAttempts.markRunningUnknown(row.getId(), "LEASE_LOST", now);
        modelAttempts.markRunningUnknown(row.getId(), "LEASE_LOST", now);
        return new Lease(row.getId(), nextEpoch);
    }

    public boolean isCurrent(Lease lease) {
        LocalDateTime now = now();
        return tasks.countCurrent(lease.taskId(), lease.epoch(), now,
            now.minusSeconds(EXECUTION_SECONDS)) == 1;
    }

    public boolean heartbeat(Lease lease) {
        LocalDateTime now = now();
        if (!isCurrent(lease)) return false;
        return tasks.heartbeat(lease.taskId(), lease.epoch(), now, now.plusSeconds(LEASE_SECONDS)) == 1;
    }

    public boolean saveProgress(Lease lease, String stage, String contextJson) {
        if (stage == null || !stage.matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw new IllegalArgumentException("任务阶段无效");
        }
        if (contextJson == null || contextJson.length() > 1_000_000) {
            throw new IllegalArgumentException("任务上下文无效");
        }
        try {
            JsonNode context = json.readTree(contextJson);
            if (!context.isObject()) throw new IllegalArgumentException("任务上下文必须是JSON对象");
        } catch (JsonProcessingException ex) { throw new IllegalArgumentException("任务上下文不是有效JSON", ex); }
        LocalDateTime now = now();
        return tasks.saveProgress(lease.taskId(), lease.epoch(), stage, contextJson,
            now, now.minusSeconds(EXECUTION_SECONDS)) == 1;
    }

    @Transactional
    public Long startStep(Lease lease, String toolName, String providerKind) {
        if (toolName == null || providerKind == null || !TOOL_NAMES.contains(toolName)
            || !TOOL_KINDS.contains(providerKind)) {
            throw new IllegalArgumentException("工具名称或类型无效");
        }
        AiTaskRow row = tasks.findByIdForUpdate(lease.taskId());
        LocalDateTime now = now();
        if (!current(row, lease, now)) return null;
        if (row.getStepCount() >= 8) throw new TaskExecutionException("STEP_LIMIT");
        if (tasks.advanceStep(lease.taskId(), lease.epoch(), toolName.toUpperCase(), now) != 1) return null;
        AiToolCallRow call = new AiToolCallRow();
        call.setTaskId(lease.taskId());
        call.setStepNo(row.getStepCount() + 1);
        call.setExecutionEpoch(lease.epoch());
        call.setToolName(toolName);
        call.setProviderKind(providerKind);
        call.setStartedAt(now);
        calls.insert(call);
        return call.getId();
    }

    @Transactional
    public boolean completeStep(Lease lease, long callId, boolean success, String summary, String errorCode) {
        AiTaskRow row = tasks.findByIdForUpdate(lease.taskId());
        LocalDateTime now = now();
        if (!current(row, lease, now)) return false;
        String safeSummary = summary == null ? null : summary.substring(0, Math.min(summary.length(), 500));
        return calls.complete(callId, lease.taskId(), lease.epoch(), success ? "SUCCESS" : "FAILED",
            safeSummary, errorCode, now) == 1;
    }

    @Transactional
    public boolean fail(Lease lease, String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            errorCode = "TASK_EXECUTION_FAILED";
        }
        LocalDateTime now = now();
        if (tasks.failOwnedLease(lease.taskId(), lease.epoch(), errorCode, now) != 1) return false;
        calls.markRunningUnknown(lease.taskId(), errorCode, now);
        ocrAttempts.markRunningUnknown(lease.taskId(), errorCode, now);
        modelAttempts.markRunningUnknown(lease.taskId(), errorCode, now);
        AiTaskRow task = tasks.findById(lease.taskId());
        notifications.recordTaskState(task.getUserId(), task.getId(), "FAILED", now);
        return true;
    }

    private boolean current(AiTaskRow row, Lease lease, LocalDateTime now) {
        return row != null && "RUNNING".equals(row.getStatus()) && !Boolean.TRUE.equals(row.getCancelFlag())
            && row.getExecutionEpoch() == lease.epoch() && row.getLeaseUntil() != null
            && row.getLeaseUntil().isAfter(now) && row.getStartedAt() != null
            && row.getStartedAt().plusSeconds(EXECUTION_SECONDS).isAfter(now);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
