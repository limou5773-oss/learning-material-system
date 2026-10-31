package com.learningmaterial.system.dto;

import com.learningmaterial.system.dto.MaterialDtos.MaterialView;
import com.learningmaterial.system.entity.ContentReviewRow;
import java.time.Instant;
import java.time.ZoneOffset;

public final class ContentReviewDtos {
    private ContentReviewDtos() { }
    public record ReviewView(String id,String materialId,int materialVersion,String title,String triggerType,
                             String status,int version,String reason,String decisionReason,Instant createdAt,
                             String ownerId,String type,String accessScope,boolean isCurrent,Instant supersededAt) {
        public static ReviewView from(ContentReviewRow r) {
            return new ReviewView(r.getId().toString(),r.getMaterialId().toString(),r.getMaterialVersion(),r.getTitle(),
                r.getTriggerType(),r.getStatus(),r.getVersion(),r.getReason(),r.getDecisionReason(),r.getCreatedAt().toInstant(ZoneOffset.UTC),
                r.getUserId().toString(),r.getMaterialType(),r.getAccessScope(),
                !"SUPERSEDED".equals(r.getStatus()) && !"DELETED".equals(r.getMaterialStatus()),
                r.getSupersededAt()==null?null:r.getSupersededAt().toInstant(ZoneOffset.UTC));
        }
    }
    public record ReviewContent(String reviewId,MaterialView material,String fileAccessPath,String auditId) { }
}
