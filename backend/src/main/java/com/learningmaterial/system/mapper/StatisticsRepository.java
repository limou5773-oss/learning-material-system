package com.learningmaterial.system.mapper;

import com.learningmaterial.system.dto.StatisticsDtos.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StatisticsRepository {
    private final JdbcTemplate jdbc;

    public StatisticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Inventory(long active, long restricted) {}

    public record Attempts(long models, long ocr, long retries, long unknownRetries) {}

    public record UploadDay(String date, LocalDateTime from, LocalDateTime to) {}

    private static final String EXTERNAL_ATTEMPTS =
            """
SELECT cost,currency FROM model_attempt WHERE dispatched=1 AND started_at>=? AND started_at<?
UNION ALL
SELECT cost,currency FROM ocr_attempt WHERE dispatched=1 AND started_at>=? AND started_at<?
""";
    private static final String KNOWN =
            "cost IS NOT NULL AND cost>=0 AND REGEXP_LIKE(currency,'^[A-Z]{3}$','c')";

    public Inventory inventory() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) active,COALESCE(SUM(access_state='RESTRICTED'),0) restricted
                FROM material WHERE status<>'DELETED'
                """,
                (rs, n) -> new Inventory(rs.getLong("active"), rs.getLong("restricted")));
    }

    public List<CountBucket> subjects() {
        return jdbc.query(
                """
SELECT COALESCE(NULLIF(TRIM(subject),''),'未分类') bucket,COUNT(*) total
FROM material WHERE status<>'DELETED' GROUP BY bucket ORDER BY total DESC,bucket ASC
""",
                (rs, n) -> new CountBucket(rs.getString("bucket"), rs.getLong("total")));
    }

    public List<DailyCount> uploads(LocalDateTime from, LocalDateTime to, List<UploadDay> days) {
        // At most 90 CASE branches; the outer indexed range still limits the scanned events.
        // Each boundary comes from Java's Shanghai rules, including historical summer time.
        StringBuilder bucket = new StringBuilder("CASE");
        List<Object> parameters = new ArrayList<>();
        for (UploadDay day : days) {
            bucket.append(" WHEN created_at>=? AND created_at<? THEN ?");
            parameters.add(day.from());
            parameters.add(day.to());
            parameters.add(day.date());
        }
        bucket.append(" END");
        parameters.add(from);
        parameters.add(to);
        return jdbc.query(
                "SELECT "
                        + bucket
                        + " day,COUNT(*) total FROM material WHERE created_at>=? AND created_at<?"
                        + " GROUP BY day ORDER BY day",
                (rs, n) -> new DailyCount(rs.getString("day"), rs.getLong("total")),
                parameters.toArray());
    }

    public List<CountBucket> taskStatuses(LocalDateTime from, LocalDateTime to) {
        return jdbc.query(
                "SELECT status,COUNT(*) total FROM ai_task WHERE created_at>=? AND created_at<?"
                        + " GROUP BY status",
                (rs, n) -> new CountBucket(rs.getString("status"), rs.getLong("total")),
                from,
                to);
    }

    public long candidates(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM ai_task t WHERE t.created_at>=? AND t.created_at<?
                AND EXISTS(SELECT 1 FROM ai_result r WHERE r.task_id=t.id
                  AND JSON_EXTRACT(r.validation_json,'$.valid')=CAST('true' AS JSON))
                """,
                Long.class,
                from,
                to);
    }

    public Attempts attempts(LocalDateTime from, LocalDateTime to) {
        long[] model =
                jdbc.queryForObject(
                        """
SELECT COUNT(*) total,
  COALESCE(SUM(CASE WHEN step_no IS NOT NULL AND step_attempt_no>1 THEN 1 ELSE 0 END),0) retries,
  COALESCE(SUM(CASE WHEN step_no IS NULL OR step_attempt_no IS NULL THEN 1 ELSE 0 END),0) unknown_retries
FROM model_attempt WHERE dispatched=1 AND started_at>=? AND started_at<?
""",
                        (rs, n) ->
                                new long[] {
                                    rs.getLong("total"),
                                    rs.getLong("retries"),
                                    rs.getLong("unknown_retries")
                                },
                        from,
                        to);
        long[] ocr =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) total,COALESCE(SUM(attempt_no>1),0) retries
                        FROM ocr_attempt WHERE dispatched=1 AND started_at>=? AND started_at<?
                        """,
                        (rs, n) -> new long[] {rs.getLong("total"), rs.getLong("retries")},
                        from,
                        to);
        return new Attempts(model[0], ocr[0], model[1] + ocr[1], model[2]);
    }

    public List<CostSummary> costs(LocalDateTime from, LocalDateTime to) {
        return jdbc.query(
                "SELECT currency,SUM(cost) amount,COUNT(*) total FROM ("
                        + EXTERNAL_ATTEMPTS
                        + ") a WHERE "
                        + KNOWN
                        + " GROUP BY currency ORDER BY currency",
                (rs, n) ->
                        new CostSummary(
                                rs.getString("currency"),
                                rs.getBigDecimal("amount").toPlainString(),
                                rs.getLong("total")),
                from,
                to,
                from,
                to);
    }

    public long unknownCosts(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN "
                        + KNOWN
                        + " THEN 0 ELSE 1 END),0) FROM ("
                        + EXTERNAL_ATTEMPTS
                        + ") a",
                Long.class,
                from,
                to,
                from,
                to);
    }
}
