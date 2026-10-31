package com.learningmaterial.system.entity;

import java.time.LocalDateTime;

public class ContentReviewRow {
    private Long id;
    private Long materialId;
    private Long userId;
    private Integer materialVersion;
    private String title;
    private String materialType;
    private String triggerType;
    private String accessScope;
    private String status;
    private Integer version;
    private String reason;
    private String decisionReason;
    private LocalDateTime createdAt;
    private Integer currentMaterialVersion;
    private String materialStatus;
    private LocalDateTime supersededAt;
    public LocalDateTime getSupersededAt() { return supersededAt; }
    public void setSupersededAt(LocalDateTime value) { supersededAt=value; }
    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    public Long getMaterialId() { return materialId; }
    public void setMaterialId(Long value) { materialId=value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId=value; }
    public Integer getMaterialVersion() { return materialVersion; }
    public void setMaterialVersion(Integer value) { materialVersion=value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title=value; }
    public String getMaterialType() { return materialType; }
    public void setMaterialType(String value) { materialType=value; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String value) { triggerType=value; }
    public String getAccessScope() { return accessScope; }
    public void setAccessScope(String value) { accessScope=value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version=value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason=value; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String value) { decisionReason=value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt=value; }
    public Integer getCurrentMaterialVersion() { return currentMaterialVersion; }
    public void setCurrentMaterialVersion(Integer value) { currentMaterialVersion=value; }
    public String getMaterialStatus() { return materialStatus; }
    public void setMaterialStatus(String value) { materialStatus=value; }
}
