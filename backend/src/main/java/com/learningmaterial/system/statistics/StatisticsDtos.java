package com.learningmaterial.system.statistics;

import java.util.List;

public final class StatisticsDtos {
    private StatisticsDtos() {}

    public record CountBucket(String key, long count) {}

    public record DailyCount(String date, long count) {}

    public record CostSummary(String currency, String amount, long knownAttempts) {}

    public record Statistics(
            String from,
            String to,
            String timezone,
            long activeMaterialCount,
            long restrictedMaterialCount,
            List<CountBucket> subjects,
            List<DailyCount> uploadTrend,
            long modelAttemptCount,
            long ocrAttemptCount,
            long retryAttemptCount,
            long unknownRetryAttempts,
            List<CountBucket> taskStatuses,
            Double taskCompletionRate,
            long candidateTaskCount,
            List<CostSummary> knownCosts,
            long unknownCostAttempts,
            List<String> metricNotes) {}
}
