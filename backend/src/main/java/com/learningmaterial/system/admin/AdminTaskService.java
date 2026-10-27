package com.learningmaterial.system.admin;

import com.learningmaterial.system.admin.AdminTaskDtos.AdminTask;
import com.learningmaterial.system.admin.AdminTaskDtos.AdminTaskStep;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Set;

@Service
public class AdminTaskService {
    private static final Set<String> GOALS = Set.of("REVIEW_CARDS", "EXTRACT_KEY_POINTS", "LINK_NOTES");
    private static final Set<String> STATUSES = Set.of("QUEUED", "RUNNING", "WAIT_CONFIRM", "SUCCEEDED", "FAILED", "CANCELLED");
    // Mirrors the identifiers emitted by TaskLeaseService, AiTaskMapper and AiTaskService.
    private static final Set<String> STAGES = Set.of("QUEUED", "STARTING", "PREPARE_SOURCE", "EXTRACT_PDF_TEXT",
        "RECOGNIZE_IMAGE", "SEARCH_PERSONAL_MATERIALS", "GENERATE_SUMMARY_CARDS", "VALIDATE_CANDIDATE",
        "PLANNER", "CANDIDATE_READY", "CONFIRMED", "FAILED", "CANCELLED", "SOURCE_DELETED", "SOURCE_RESTRICTED");
    private static final Set<String> TOOLS = Set.of("prepare_source", "extract_pdf_text", "recognize_image",
        "search_personal_materials", "generate_summary_cards", "validate_candidate", "planner");
    private static final Set<String> PROVIDERS = Set.of("LOCAL", "MODEL", "OCR");
    private static final Set<String> STEP_STATUSES = Set.of("RUNNING", "SUCCESS", "FAILED", "UNKNOWN");
    // Only current internal task, model and OCR codes are public. A syntactically valid arbitrary
    // string is still private; never forward a provider message or a historical free-form value.
    private static final Set<String> ERROR_CODES = Set.of(
        "AGENT_ACTION_NOT_ALLOWED", "AGENT_PLAN_INVALID", "AGENT_PLANNER_LIMIT", "AGENT_RELATED_CHANGED",
        "AGENT_SEARCH_NOT_ALLOWED", "AGENT_STOP_SOURCE_UNREADABLE", "AGENT_STOP_NO_USABLE_SOURCE",
        "AGENT_STOP_UNSUPPORTED_REQUEST", "EXTERNAL_PROCESSING_CONSENT_REQUIRED", "INVALID_CANDIDATE",
        "INVALID_PAGE_RANGE", "INVALID_TASK_CONFIG", "INVALID_TOOL_ARGUMENTS", "LEASE_LOST", "LEASE_RECOVERED",
        "MODEL_ATTEMPT_LIMIT", "MODEL_AUTH_FAILED", "MODEL_CONFIG_CHANGED", "MODEL_CONFIG_INVALID",
        "MODEL_CONNECTION_FAILED", "MODEL_CONTENT_FILTERED", "MODEL_CREDENTIALS_INVALID", "MODEL_EMPTY_RESULT",
        "MODEL_INPUT_LIMIT", "MODEL_INTERRUPTED", "MODEL_INVALID_INPUT", "MODEL_INVALID_RESPONSE",
        "MODEL_NOT_CONFIGURED", "MODEL_OUTPUT_INVALID", "MODEL_OUTPUT_TRUNCATED", "MODEL_PROVIDER_UNAVAILABLE",
        "MODEL_PUBLICATION_INCOMPLETE", "MODEL_QUOTA_EXCEEDED", "MODEL_RATE_LIMITED", "MODEL_REDIRECT_REJECTED",
        "MODEL_REQUEST_FAILED", "MODEL_REQUEST_REJECTED", "MODEL_SOURCE_TEXT_REQUIRED", "MODEL_TEXT_LIMIT_EXCEEDED",
        "MODEL_TIMEOUT", "OCR_ATTEMPT_LIMIT", "OCR_AUTH_FAILED", "OCR_CACHE_CONFLICT", "OCR_CONNECTION_FAILED",
        "OCR_CREDENTIALS_INVALID", "OCR_EMPTY_RESULT", "OCR_FILE_UNAVAILABLE", "OCR_IMAGE_INVALID",
        "OCR_IMAGE_TOO_LARGE", "OCR_INTERRUPTED", "OCR_INVALID_INPUT", "OCR_INVALID_RESPONSE", "OCR_NOT_CONFIGURED",
        "OCR_PROVIDER_FAILED", "OCR_PROVIDER_INVALID", "OCR_PROVIDER_UNAVAILABLE", "OCR_PUBLICATION_INCOMPLETE",
        "OCR_RATE_LIMITED", "OCR_REDIRECT_REJECTED", "OCR_REQUEST_FAILED", "OCR_RESPONSE_INVALID",
        "OCR_SERVICE_NOT_ENABLED", "OCR_TEXT_LIMIT_EXCEEDED", "OCR_TIMEOUT", "PDF_CACHE_CONFLICT", "PDF_ENCRYPTED",
        "PDF_EXTRACTION_FORBIDDEN", "PDF_FILE_UNAVAILABLE", "PDF_OCR_REQUIRED", "PDF_READ_FAILED",
        "PDF_TEXT_LIMIT_EXCEEDED", "PROCESSOR_INCOMPLETE", "PROCESSOR_MODE_CHANGED", "PROCESSOR_NOT_CONFIGURED",
        "SOURCE_CHANGED", "SOURCE_CHUNK_CONFLICT", "SOURCE_DELETED", "SOURCE_RESTRICTED", "SOURCE_TEXT_NOT_AVAILABLE",
        "SOURCE_UNAVAILABLE", "SOURCE_VERSION_CHANGED", "STEP_LIMIT", "TASK_CANCELLED", "TASK_EXECUTION_FAILED",
        "TASK_TIMEOUT", "UNSUPPORTED_SOURCE_TYPE");
    private final AdminTaskMapper tasks;

    public AdminTaskService(AdminTaskMapper tasks) {
        this.tasks = tasks;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageView<AdminTask> list(String status, String rawFrom, String rawTo, int page, int pageSize) {
        validatePage(page, pageSize);
        status = status == null || status.isBlank() ? null : status.trim();
        if (status != null && !STATUSES.contains(status)) throw invalid();
        LocalDateTime from = parse(rawFrom), to = parse(rawTo);
        if (from != null && to != null && !from.isBefore(to)) throw invalid();
        // Rows are stored at microsecond precision; ceiling preserves both >= and < comparisons
        // when a valid ISO timestamp supplies a finer fractional second.
        from = ceilingMicros(from);
        to = ceilingMicros(to);
        long total = tasks.count(status, from, to);
        return new PageView<>(tasks.list(status, from, to, (long) (page - 1) * pageSize, pageSize)
            .stream().map(AdminTaskService::taskView).toList(), page, pageSize, total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageView<AdminTaskStep> steps(String rawId, int page, int pageSize) {
        long id;
        try {
            if (!rawId.matches("[1-9][0-9]*")) throw new NumberFormatException();
            id = Long.parseLong(rawId);
        } catch (NumberFormatException ex) { throw notFound(); }
        validatePage(page, pageSize);
        if (!tasks.exists(id)) throw notFound();
        long total = tasks.countSteps(id);
        return new PageView<>(tasks.listSteps(id, (long) (page - 1) * pageSize, pageSize)
            .stream().map(AdminTaskService::stepView).toList(), page, pageSize, total);
    }

    private static AdminTask taskView(AdminTaskMapper.TaskRow row) {
        return new AdminTask(Long.toString(row.id()), Long.toString(row.userId()), Long.toString(row.materialId()),
            known(row.goalType(), GOALS), known(row.status(), STATUSES), row.stage() == null ? null : known(row.stage(), STAGES),
            row.stepCount(), error(row.errorCode()), instant(row.createdAt()), instant(row.startedAt()), instant(row.finishedAt()),
            duration(row.startedAt(), row.finishedAt()));
    }

    private static AdminTaskStep stepView(AdminTaskMapper.StepRow row) {
        return new AdminTaskStep(row.stepNo(), row.attemptNo(), known(row.toolName(), TOOLS),
            known(row.providerKind(), PROVIDERS), known(row.status(), STEP_STATUSES), error(row.errorCode()),
            instant(row.startedAt()), instant(row.finishedAt()), duration(row.startedAt(), row.finishedAt()));
    }

    private static String known(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : "UNKNOWN";
    }

    private static String error(String value) {
        if (value == null || value.isEmpty()) return null;
        return ERROR_CODES.contains(value) ? value : "UNRECOGNIZED_ERROR";
    }

    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }

    private static Long duration(LocalDateTime started, LocalDateTime finished) {
        return started == null || finished == null || finished.isBefore(started) ? null : Duration.between(started, finished).toMillis();
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw invalid();
    }

    private static LocalDateTime parse(String raw) {
        if (raw == null) return null;
        if (!raw.matches("(?:[0-9]{4}|[+-][0-9]{4,})-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")) throw badDate();
        LocalDateTime value;
        try { value = OffsetDateTime.parse(raw).toLocalDateTime(); }
        catch (DateTimeParseException ex) { throw badDate(); }
        if (value.getYear() < 1000 || value.getYear() > 9999
            || value.isAfter(LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000))) throw invalid();
        return value;
    }

    private static LocalDateTime ceilingMicros(LocalDateTime value) {
        if (value == null || value.getNano() % 1000 == 0) return value;
        return value.plusNanos(1000 - value.getNano() % 1000);
    }

    private static ApiException invalid() { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", "查询范围、状态或分页参数无效"); }
    private static ApiException badDate() { return new ApiException(HttpStatus.BAD_REQUEST, "BAD_QUERY", "时间须为带Z的UTC ISO时间"); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在"); }
}
