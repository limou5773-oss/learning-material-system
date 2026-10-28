package com.learningmaterial.system.dto;

import java.time.Instant;

public final class AdminTaskDtos {
    private AdminTaskDtos() { }

    public record AdminTask(String id, String userId, String materialId, String goalType,
                            String status, String stage, int stepCount, String errorCode,
                            Instant createdAt, Instant startedAt, Instant finishedAt, Long durationMs) { }

    public record AdminTaskStep(int stepNo, int attemptNo, String toolName, String providerKind,
                                String status, String errorCode, Instant startedAt,
                                Instant finishedAt, Long durationMs) { }
}
