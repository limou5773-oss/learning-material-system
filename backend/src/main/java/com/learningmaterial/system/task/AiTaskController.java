package com.learningmaterial.system.task;

import com.learningmaterial.system.auth.AuthPrincipal;
import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import com.learningmaterial.system.task.AiTaskDtos.TaskInput;
import com.learningmaterial.system.task.AiTaskDtos.TaskView;
import com.learningmaterial.system.task.AiTaskDtos.RetryInput;
import com.learningmaterial.system.task.AiTaskDtos.TaskStepView;
import com.learningmaterial.system.task.AiTaskDtos.CandidateResult;
import com.learningmaterial.system.task.AiTaskDtos.SourceView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ai-tasks")
public class AiTaskController {
    private final AiTaskService tasks;
    private final TaskCandidateService candidates;

    public AiTaskController(AiTaskService tasks, TaskCandidateService candidates) {
        this.tasks = tasks;
        this.candidates = candidates;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TaskView>> submit(@AuthenticationPrincipal AuthPrincipal principal,
                                                         @Valid @RequestBody TaskInput input,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.of(tasks.submit(principal.userId(), input), request));
    }

    @GetMapping
    public ApiResponse<PageView<TaskView>> list(@AuthenticationPrincipal AuthPrincipal principal,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize,
                                                 HttpServletRequest request) {
        return ApiResponse.of(tasks.list(principal.userId(), status, page, pageSize), request);
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskView> get(@AuthenticationPrincipal AuthPrincipal principal,
                                     @PathVariable String id, HttpServletRequest request) {
        return ApiResponse.of(tasks.get(principal.userId(), id), request);
    }

    @GetMapping("/{id}/result")
    public ApiResponse<CandidateResult> result(@AuthenticationPrincipal AuthPrincipal principal,
                                                @PathVariable String id, HttpServletRequest request) {
        long taskId = Long.parseLong(tasks.get(principal.userId(), id).id());
        return ApiResponse.of(candidates.getResult(principal.userId(), taskId), request);
    }

    @GetMapping("/{id}/sources")
    public ApiResponse<List<SourceView>> sources(@AuthenticationPrincipal AuthPrincipal principal,
                                                  @PathVariable String id, HttpServletRequest request) {
        long taskId = Long.parseLong(tasks.get(principal.userId(), id).id());
        return ApiResponse.of(candidates.getSources(principal.userId(), taskId), request);
    }

    @GetMapping("/{id}/steps")
    public ApiResponse<PageView<TaskStepView>> steps(@AuthenticationPrincipal AuthPrincipal principal,
                                                      @PathVariable String id,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int pageSize,
                                                      HttpServletRequest request) {
        return ApiResponse.of(tasks.steps(principal.userId(), id, page, pageSize), request);
    }

    @PostMapping("/{id}/cancellations")
    public ApiResponse<TaskView> cancel(@AuthenticationPrincipal AuthPrincipal principal,
                                        @PathVariable String id, HttpServletRequest request) {
        return ApiResponse.of(tasks.cancel(principal.userId(), id), request);
    }

    @PostMapping("/{id}/retries")
    public ResponseEntity<ApiResponse<TaskView>> retry(@AuthenticationPrincipal AuthPrincipal principal,
                                                        @PathVariable String id, @Valid @RequestBody RetryInput input,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.of(tasks.retry(principal.userId(), id, input), request));
    }
}
