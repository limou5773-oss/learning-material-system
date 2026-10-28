package com.learningmaterial.system.entity;

import java.time.LocalDateTime;

public record WechatBindingRow(
        long id, long userId, String appId, String openId,
        LocalDateTime createdAt, LocalDateTime updatedAt) { }
