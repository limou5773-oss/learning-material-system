package com.learningmaterial.system.controller;

import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.dto.AdminTaskDtos.AdminTask;
import com.learningmaterial.system.dto.AdminTaskDtos.AdminTaskStep;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.service.AdminTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai-tasks")
public class AdminTaskController {
    private final AdminTaskService tasks;

    public AdminTaskController(AdminTaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public ApiResponse<PageView<AdminTask>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize,
                                                HttpServletRequest request) {
        return ApiResponse.of(tasks.list(status, from, to, page, pageSize), request);
    }

    @GetMapping("/{id}/steps")
    public ApiResponse<PageView<AdminTaskStep>> steps(@PathVariable String id,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int pageSize,
                                                     HttpServletRequest request) {
        return ApiResponse.of(tasks.steps(id, page, pageSize), request);
    }
}
