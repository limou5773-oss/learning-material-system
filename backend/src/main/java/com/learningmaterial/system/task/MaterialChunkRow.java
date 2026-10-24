package com.learningmaterial.system.task;

import java.time.LocalDateTime;

public class MaterialChunkRow {
    private Long id;
    private Long materialId;
    private Integer materialVersion;
    private Integer chunkNo;
    private Integer pageNo;
    private String content;
    private String extractionMethod;
    private String qualityNote;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMaterialId() { return materialId; }
    public void setMaterialId(Long materialId) { this.materialId = materialId; }
    public Integer getMaterialVersion() { return materialVersion; }
    public void setMaterialVersion(Integer materialVersion) { this.materialVersion = materialVersion; }
    public Integer getChunkNo() { return chunkNo; }
    public void setChunkNo(Integer chunkNo) { this.chunkNo = chunkNo; }
    public Integer getPageNo() { return pageNo; }
    public void setPageNo(Integer pageNo) { this.pageNo = pageNo; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getExtractionMethod() { return extractionMethod; }
    public void setExtractionMethod(String extractionMethod) { this.extractionMethod = extractionMethod; }
    public String getQualityNote() { return qualityNote; }
    public void setQualityNote(String qualityNote) { this.qualityNote = qualityNote; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
