package com.learningmaterial.system.controller;

import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.dto.NotificationDtos.*;
import com.learningmaterial.system.security.AuthPrincipal;
import com.learningmaterial.system.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping
    public ApiResponse<PageView<NotificationView>> list(@AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String unreadOnly, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize, HttpServletRequest request) {
        return ApiResponse.of(notifications.list(principal.userId(), unreadOnly, page, pageSize), request);
    }

    @PutMapping("/{id}/read-state")
    public ApiResponse<NotificationView> read(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id,
            @Valid @RequestBody ReadStateInput input, HttpServletRequest request) {
        return ApiResponse.of(notifications.setReadState(principal.userId(), id, input.isRead()), request);
    }
}
