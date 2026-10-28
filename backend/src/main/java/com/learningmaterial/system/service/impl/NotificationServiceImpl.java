package com.learningmaterial.system.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.dto.NotificationDtos.NotificationView;
import com.learningmaterial.system.entity.NotificationRow;
import com.learningmaterial.system.mapper.NotificationMapper;
import com.learningmaterial.system.service.NotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationServiceImpl implements NotificationService {
    private final NotificationMapper notifications;
    private final Clock clock;
    private final ObjectMapper json;

    public NotificationServiceImpl(NotificationMapper notifications, Clock clock, ObjectMapper json) {
        this.notifications = notifications;
        this.clock = clock;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTaskState(long userId, long taskId, String state, LocalDateTime now) {
        String type, content;
        switch (state) {
            case "WAIT_CONFIRM" -> { type = "RESULT_READY"; content = "学习资料处理结果已生成，请查看并确认。"; }
            case "FAILED" -> { type = "TASK_FAILED"; content = "学习资料处理任务未能完成，请查看任务详情。"; }
            default -> throw new IllegalArgumentException("不支持的通知任务状态");
        }
        notifications.insertEvent(userId, taskId, type, "task:" + taskId + ":" + state, content, now);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<NotificationView> list(long userId, String unreadOnly, int page, int pageSize) {
        if (unreadOnly == null) unreadOnly = "false";
        if (!"true".equals(unreadOnly) && !"false".equals(unreadOnly))
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_QUERY", "未读筛选参数格式错误");
        if (page < 1 || pageSize < 1 || pageSize > 100)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAGE", "分页参数无效");
        boolean unread = Boolean.parseBoolean(unreadOnly);
        long total = notifications.countOwned(userId, unread);
        return new PageView<>(notifications.listOwned(userId, unread, pageSize, (long) (page - 1) * pageSize)
            .stream().map(row -> NotificationView.from(row, json)).toList(), page, pageSize, total);
    }

    @Override
    @Transactional
    public NotificationView setReadState(long userId, String id, boolean isRead) {
        long notificationId = parseId(id);
        notifications.setReadState(userId, notificationId, isRead, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        NotificationRow row = notifications.findOwned(userId, notificationId);
        if (row == null) throw notFound();
        return NotificationView.from(row, json);
    }

    private static long parseId(String id) {
        if (id != null && id.matches("[1-9][0-9]*")) {
            try { return Long.parseLong(id); } catch (NumberFormatException ignored) { }
        }
        throw notFound();
    }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在");
    }
}
