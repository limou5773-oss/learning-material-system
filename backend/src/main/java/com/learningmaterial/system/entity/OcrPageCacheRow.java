package com.learningmaterial.system.entity;

import java.time.LocalDateTime;

public class OcrPageCacheRow {
    private Long materialId;
    private Integer materialVersion;
    private Integer pageNo;
    private String providerKey;
    private String extractionStatus;
    private String pageText;
    private Double confidence;
    private String requestId;
    private LocalDateTime createdAt;

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long value) {
        materialId = value;
    }

    public Integer getMaterialVersion() {
        return materialVersion;
    }

    public void setMaterialVersion(Integer value) {
        materialVersion = value;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer value) {
        pageNo = value;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String value) {
        providerKey = value;
    }

    public String getExtractionStatus() {
        return extractionStatus;
    }

    public void setExtractionStatus(String value) {
        extractionStatus = value;
    }

    public String getPageText() {
        return pageText;
    }

    public void setPageText(String value) {
        pageText = value;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double value) {
        confidence = value;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String value) {
        requestId = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        createdAt = value;
    }
}
