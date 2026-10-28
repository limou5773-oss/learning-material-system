package com.learningmaterial.system.dto;



public record OcrExtractionOutcome(
        PdfExtraction extraction, int dispatched, int cacheHits) {
    public String summary() {
        return "OCR可能已发送" + dispatched + "次，命中缓存" + cacheHits + "页；已核验来源，未记录识别正文。";
    }
}
