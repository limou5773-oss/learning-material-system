package com.learningmaterial.system.statistics;

import com.learningmaterial.system.common.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/statistics")
public class StatisticsController {
    private final StatisticsService statistics;

    public StatisticsController(StatisticsService statistics) {
        this.statistics = statistics;
    }

    @GetMapping
    public ApiResponse<StatisticsDtos.Statistics> get(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String timezone,
            HttpServletRequest request) {
        return ApiResponse.of(statistics.get(from, to, timezone), request);
    }
}
