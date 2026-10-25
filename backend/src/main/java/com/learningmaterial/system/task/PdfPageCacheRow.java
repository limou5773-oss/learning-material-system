package com.learningmaterial.system.task;

import java.time.LocalDateTime;

public class PdfPageCacheRow {
    private Long materialId;
    private Integer materialVersion;
    private Integer pageNo;
    private Integer totalPages;
    private String extractionStatus;
    private String pageText;
    private LocalDateTime createdAt;

    public Long getMaterialId() { return materialId; }
    public void setMaterialId(Long value) { materialId = value; }
    public Integer getMaterialVersion() { return materialVersion; }
    public void setMaterialVersion(Integer value) { materialVersion = value; }
    public Integer getPageNo() { return pageNo; }
    public void setPageNo(Integer value) { pageNo = value; }
    public Integer getTotalPages() { return totalPages; }
    public void setTotalPages(Integer value) { totalPages = value; }
    public String getExtractionStatus() { return extractionStatus; }
    public void setExtractionStatus(String value) { extractionStatus = value; }
    public String getPageText() { return pageText; }
    public void setPageText(String value) { pageText = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
