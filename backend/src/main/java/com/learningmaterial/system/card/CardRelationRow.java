package com.learningmaterial.system.card;

import java.time.LocalDateTime;

public class CardRelationRow {
    private Long id, userId, sourceCardId, targetCardId;
    private String relationType, evidenceText;
    private LocalDateTime createdAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSourceCardId() { return sourceCardId; }
    public void setSourceCardId(Long sourceCardId) { this.sourceCardId = sourceCardId; }
    public Long getTargetCardId() { return targetCardId; }
    public void setTargetCardId(Long targetCardId) { this.targetCardId = targetCardId; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public String getEvidenceText() { return evidenceText; }
    public void setEvidenceText(String evidenceText) { this.evidenceText = evidenceText; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
