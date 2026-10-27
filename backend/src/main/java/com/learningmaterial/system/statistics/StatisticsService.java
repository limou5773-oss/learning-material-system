package com.learningmaterial.system.statistics;

import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.statistics.StatisticsDtos.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StatisticsService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<String> STATUSES =
            List.of("QUEUED", "RUNNING", "WAIT_CONFIRM", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final List<String> NOTES =
            List.of(
                    "当前库存和学科为查询快照中全部未删除资料，含归档和受限资料，不受日期范围筛选。",
                    "期间采用上海自然日和左闭右开范围；上传按创建时间计数，包括后来删除的资料；任务按创建时间选择并展示当前状态。",
                    "模型与OCR次数来自已发送尝试，含重试、失败、RUNNING及UNKNOWN；可能发送不代表厂商成功或账单确认，缓存和本地工具不计。",
                    "已知重试按模型同任务同逻辑步骤、OCR同任务同页的后续尝试计算；历史模型步骤归属缺失单列为未知重试尝试，不当作零重试。",
                    "候选任务表示曾产生validation.valid=true的合格候选，包含MOCK测试及后来INVALIDATED的候选，不等于真实模型质量或任务完成数量。",
                    "完成率为SUCCEEDED/(SUCCEEDED+FAILED)，其他状态不进入分母；分母为零时返回null。",
                    "费用仅汇总明确非负金额及有效大写币种，按币种分别统计；明确零金额有效，未知金额或币种单列，不补零、不猜价格。");
    private final StatisticsRepository repository;

    public StatisticsService(StatisticsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statistics get(String rawFrom, String rawTo, String timezone) {
        Instant from = parse(rawFrom), to = parse(rawTo);
        if (timezone == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_QUERY", "缺少timezone查询参数");
        if (!"Asia/Shanghai".equals(timezone)) throw invalid();
        ZonedDateTime start = from.atZone(SHANGHAI), end = to.atZone(SHANGHAI);
        if (!start.toLocalTime().equals(LocalTime.MIDNIGHT)
                || !end.toLocalTime().equals(LocalTime.MIDNIGHT)
                || !from.isBefore(to)
                || ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) > 90)
            throw invalid();
        LocalDateTime lower = LocalDateTime.ofInstant(from, ZoneOffset.UTC),
                upper = LocalDateTime.ofInstant(to, ZoneOffset.UTC);
        var inventory = repository.inventory();
        var subjects = repository.subjects();
        List<StatisticsRepository.UploadDay> uploadDays =
                start.toLocalDate()
                        .datesUntil(end.toLocalDate())
                        .map(
                                date ->
                                        new StatisticsRepository.UploadDay(
                                                date.toString(),
                                                LocalDateTime.ofInstant(
                                                        date.atStartOfDay(SHANGHAI).toInstant(),
                                                        ZoneOffset.UTC),
                                                LocalDateTime.ofInstant(
                                                        date.plusDays(1)
                                                                .atStartOfDay(SHANGHAI)
                                                                .toInstant(),
                                                        ZoneOffset.UTC)))
                        .toList();
        Map<String, DailyCount> uploads =
                repository.uploads(lower, upper, uploadDays).stream()
                        .collect(Collectors.toMap(DailyCount::date, Function.identity()));
        List<DailyCount> days =
                start.toLocalDate()
                        .datesUntil(end.toLocalDate())
                        .map(
                                date ->
                                        uploads.getOrDefault(
                                                date.toString(),
                                                new DailyCount(date.toString(), 0)))
                        .toList();
        Map<String, Long> counts =
                repository.taskStatuses(lower, upper).stream()
                        .collect(Collectors.toMap(CountBucket::key, CountBucket::count));
        List<CountBucket> statuses =
                STATUSES.stream()
                        .map(status -> new CountBucket(status, counts.getOrDefault(status, 0L)))
                        .toList();
        long succeeded = counts.getOrDefault("SUCCEEDED", 0L),
                denominator = succeeded + counts.getOrDefault("FAILED", 0L);
        var attempts = repository.attempts(lower, upper);
        return new Statistics(
                from.toString(),
                to.toString(),
                timezone,
                inventory.active(),
                inventory.restricted(),
                subjects,
                days,
                attempts.models(),
                attempts.ocr(),
                attempts.retries(),
                attempts.unknownRetries(),
                statuses,
                denominator == 0 ? null : (double) succeeded / denominator,
                repository.candidates(lower, upper),
                repository.costs(lower, upper),
                repository.unknownCosts(lower, upper),
                NOTES);
    }

    private static Instant parse(String value) {
        if (value == null
                || !value.matches(
                        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z"))
            throw badQuery();
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ex) {
            throw badQuery();
        }
    }

    private static ApiException badQuery() {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_QUERY", "日期参数必须为带Z的UTC ISO8601时间");
    }

    private static ApiException invalid() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_INPUT",
                "仅支持Asia/Shanghai自然日、正向且不超过90天的日期范围");
    }
}
