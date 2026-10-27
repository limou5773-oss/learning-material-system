package com.learningmaterial.system.review;

import com.learningmaterial.system.material.MaterialDtos.MaterialView;
import java.time.Instant;
import java.time.ZoneOffset;

public final class ContentReviewDtos {
    private ContentReviewDtos() { }
    public record ReviewView(String id,String materialId,int materialVersion,String title,String triggerType,
                             String status,int version,String reason,String decisionReason,Instant createdAt,
                             String ownerId,String type,String accessScope,boolean isCurrent) {
        static ReviewView from(ContentReviewRow r) {
            return new ReviewView(r.getId().toString(),r.getMaterialId().toString(),r.getMaterialVersion(),r.getTitle(),
                r.getTriggerType(),r.getStatus(),r.getVersion(),r.getReason(),r.getDecisionReason(),r.getCreatedAt().toInstant(ZoneOffset.UTC),
                r.getUserId().toString(),r.getMaterialType(),r.getAccessScope(),
                r.getMaterialVersion().equals(r.getCurrentMaterialVersion()) && !"DELETED".equals(r.getMaterialStatus()));
        }
    }
    public record ReviewContent(String reviewId,MaterialView material,String fileAccessPath,String auditId) { }
}
