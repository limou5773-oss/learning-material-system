package com.learningmaterial.system.card;

import java.time.LocalDateTime;

public class CardLayoutRow {
    private Long id, userId;
    private String layoutKey, itemsJson;
    private Integer version;
    private LocalDateTime createdAt, updatedAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getLayoutKey() { return layoutKey; }
    public void setLayoutKey(String layoutKey) { this.layoutKey = layoutKey; }
    public String getItemsJson() { return itemsJson; }
    public void setItemsJson(String itemsJson) { this.itemsJson = itemsJson; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
