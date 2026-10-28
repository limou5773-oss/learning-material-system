package com.learningmaterial.system.dto;

import com.learningmaterial.system.entity.AiTaskRow;
import com.learningmaterial.system.entity.AiToolCallRow;
import com.learningmaterial.system.entity.MaterialChunkRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
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
        public static TaskView from(AiTaskRow row) {
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
        public static TaskStepView from(AiToolCallRow row) {
            BigDecimal cost = row.getCostAmount();
            return new TaskStepView(row.getStepNo(), row.getAttemptNo(), row.getToolName(),
                row.getProviderKind(), row.getStatus(), row.getOutputSummary(), row.getErrorCode(),
                row.getStartedAt().toInstant(ZoneOffset.UTC),
                row.getFinishedAt() == null ? null : row.getFinishedAt().toInstant(ZoneOffset.UTC),
                cost == null ? null : cost.toPlainString(), row.getCurrency());
        }
    }

    public record SourceRef(@NotBlank @Pattern(regexp = "[1-9][0-9]*") String chunkId,
                            @NotBlank @Size(max = 2000) String quoteText,
                            @NotNull @Min(0) Integer startOffset,
                            @NotNull @Min(1) Integer endOffset) { }

    public record CandidateCard(@NotBlank @Size(max = 64) String candidateKey,
                                @NotBlank @Size(max = 255) String title,
                                @NotBlank @Size(max = 8000) String content,
                                @Size(max = 2000) String summary, @Size(max = 64) String subject,
                                @Size(max = 10) List<@NotBlank @Size(max = 64) String> tags,
                                @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid SourceRef> sources,
                                @Size(max = 64) String parentCandidateKey) { }

    public record CardEndpoint(@Size(min = 1, max = 64) String candidateKey,
                               @Pattern(regexp = "[1-9][0-9]*") String cardId,
                               @Min(1) Integer cardVersion) { }

    public record CandidateRelation(@NotBlank @Size(max = 64) String relationKey,
                                    @NotNull @Valid CardEndpoint source,
                                    @NotNull @Valid CardEndpoint target,
                                    @NotBlank @Pattern(regexp = "RELATED|PREREQUISITE|EXAMPLE") String type,
                                    @NotBlank @Size(max = 2000) String evidenceText) { }

    public enum TagMode { MERGE, REPLACE }

    public record ConfirmationInput(@NotNull @Min(1) Integer resultVersion,
                                     @NotNull @Min(1) Integer sourceVersion,
                                     @NotNull @Size(max = 4000) String summary,
                                     @NotNull @Size(max = 10) List<@NotBlank @Size(max = 64) String> tags,
                                     @NotNull TagMode tagMode,
                                     @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid CandidateCard> cards,
                                     @NotNull @Size(max = 60) List<@NotNull @Valid CandidateRelation> relations) { }

    public record ConfirmationResult(String taskId, String status, List<String> cardIds,
                                      int resultingMaterialVersion, Instant confirmedAt) { }

    public record GenerationInfo(String provider, String model, String promptVersion) { }

    public record CandidateResult(String taskId, int resultVersion, int sourceVersion,
                                  String generationMode, String summary, List<String> tags,
                                  List<CandidateCard> cards, List<CandidateRelation> relations,
                                  Integer totalPages, List<Integer> processedPages, List<String> warnings,
                                  GenerationInfo generationInfo) {
        public CandidateResult(String taskId, int resultVersion, int sourceVersion,
                               String generationMode, String summary, List<String> tags,
                               List<CandidateCard> cards, List<CandidateRelation> relations,
                               Integer totalPages, List<Integer> processedPages, List<String> warnings) {
            this(taskId, resultVersion, sourceVersion, generationMode, summary, tags, cards, relations,
                totalPages, processedPages, warnings, null);
        }
    }

    public record SourceView(String chunkId, String materialId, int materialVersion, int chunkNo,
                             Integer pageNo, String extractionMethod, String content, String qualityNote) {
        public static SourceView from(MaterialChunkRow row) {
            return new SourceView(row.getId().toString(), row.getMaterialId().toString(),
                row.getMaterialVersion(), row.getChunkNo(), row.getPageNo(),
                row.getExtractionMethod(), row.getContent(), row.getQualityNote());
        }
    }
}
