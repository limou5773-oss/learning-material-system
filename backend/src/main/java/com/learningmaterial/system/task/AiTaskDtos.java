package com.learningmaterial.system.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;

public final class AiTaskDtos {
    private AiTaskDtos() { }

    public enum GoalType { REVIEW_CARDS, EXTRACT_KEY_POINTS, LINK_NOTES }

    public record PageRange(@Min(1) int from, @Min(1) int to) { }

    public record TaskInput(
        @NotBlank String materialId,
        @NotNull @Min(1) Integer materialVersion,
        @NotBlank @Size(max = 64) String requestKey,
        @NotNull GoalType goalType,
        @Size(max = 1000) String instruction,
        @Valid PageRange pageRange,
        boolean allowRelatedSearch,
        @NotNull Boolean externalProcessingConsent
    ) { }

    public record TaskView(
        String id, String materialId, int materialVersion, String parentTaskId,
        String goalType, String status, String stage, int stepCount, String errorCode,
        boolean canCancel, boolean canRetry, Instant createdAt, Instant startedAt, Instant finishedAt
    ) {
        static TaskView from(AiTaskRow row) {
            return new TaskView(row.getId().toString(), row.getMaterialId().toString(),
                row.getMaterialVersion(), row.getParentTaskId() == null ? null : row.getParentTaskId().toString(),
                row.getGoalType(), row.getStatus(), row.getStage(), row.getStepCount(), row.getErrorCode(),
                java.util.List.of("QUEUED", "RUNNING", "WAIT_CONFIRM").contains(row.getStatus()),
                java.util.List.of("FAILED", "CANCELLED").contains(row.getStatus())
                    && !"SOURCE_DELETED".equals(row.getErrorCode()),
                row.getCreatedAt().toInstant(ZoneOffset.UTC),
                row.getStartedAt() == null ? null : row.getStartedAt().toInstant(ZoneOffset.UTC),
                row.getFinishedAt() == null ? null : row.getFinishedAt().toInstant(ZoneOffset.UTC));
        }
    }

    public record RetryInput(@NotBlank @Size(max = 64) String requestKey,
                             @NotNull @Min(1) Integer materialVersion,
                             @NotNull Boolean externalProcessingConsent) { }

    public record TaskStepView(int stepNo, int attemptNo, String toolName, String providerKind,
                               String status, String outputSummary, String errorCode,
                               Instant startedAt, Instant finishedAt, String costAmount, String currency) {
        static TaskStepView from(AiToolCallRow row) {
            BigDecimal cost = row.getCostAmount();
            return new TaskStepView(row.getStepNo(), row.getAttemptNo(), row.getToolName(),
                row.getProviderKind(), row.getStatus(), row.getOutputSummary(), row.getErrorCode(),
                row.getStartedAt().toInstant(ZoneOffset.UTC),
                row.getFinishedAt() == null ? null : row.getFinishedAt().toInstant(ZoneOffset.UTC),
                cost == null ? null : cost.toPlainString(), row.getCurrency());
        }
    }

    public record SourceRef(String chunkId, String quoteText, int startOffset, int endOffset) { }

    public record CandidateCard(String candidateKey, String title, String content, String summary,
                                String subject, List<String> tags, List<SourceRef> sources,
                                String parentCandidateKey) { }

    public record CandidateResult(String taskId, int resultVersion, int sourceVersion,
                                  String generationMode, String summary, List<String> tags,
                                  List<CandidateCard> cards, List<Object> relations,
                                  Integer totalPages, List<Integer> processedPages, List<String> warnings) { }

    public record SourceView(String chunkId, String materialId, int materialVersion, int chunkNo,
                             Integer pageNo, String extractionMethod, String content, String qualityNote) {
        static SourceView from(MaterialChunkRow row) {
            return new SourceView(row.getId().toString(), row.getMaterialId().toString(),
                row.getMaterialVersion(), row.getChunkNo(), row.getPageNo(),
                row.getExtractionMethod(), row.getContent(), row.getQualityNote());
        }
    }
}
