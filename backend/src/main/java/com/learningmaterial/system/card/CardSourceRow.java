package com.learningmaterial.system.card;

public class CardSourceRow {
    private Long chunkId, materialId;
    private Integer materialVersion, pageNo;
    private String materialTitle, quoteText, content, materialStatus, accessState;
    private String extractionMethod, qualityNote;
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public Long getMaterialId() { return materialId; }
    public void setMaterialId(Long materialId) { this.materialId = materialId; }
    public Integer getMaterialVersion() { return materialVersion; }
    public void setMaterialVersion(Integer materialVersion) { this.materialVersion = materialVersion; }
    public Integer getPageNo() { return pageNo; }
    public void setPageNo(Integer pageNo) { this.pageNo = pageNo; }
    public String getMaterialTitle() { return materialTitle; }
    public void setMaterialTitle(String materialTitle) { this.materialTitle = materialTitle; }
    public String getQuoteText() { return quoteText; }
    public void setQuoteText(String quoteText) { this.quoteText = quoteText; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMaterialStatus() { return materialStatus; }
    public void setMaterialStatus(String materialStatus) { this.materialStatus = materialStatus; }
    public String getAccessState() { return accessState; }
    public void setAccessState(String accessState) { this.accessState = accessState; }
    public String getExtractionMethod() { return extractionMethod; }
    public void setExtractionMethod(String extractionMethod) { this.extractionMethod = extractionMethod; }
    public String getQualityNote() { return qualityNote; }
    public void setQualityNote(String qualityNote) { this.qualityNote = qualityNote; }
}
