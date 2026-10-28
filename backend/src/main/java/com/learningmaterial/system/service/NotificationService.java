package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.dto.NotificationDtos.NotificationView;
import java.time.LocalDateTime;

/** NotificationService 的业务契约。 */
public interface NotificationService {
    void recordTaskState(long userId, long taskId, String state, LocalDateTime now);

    PageView<NotificationView> list(long userId, String unreadOnly, int page, int pageSize);

    NotificationView setReadState(long userId, String id, boolean isRead);

}
