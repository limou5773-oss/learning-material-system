package com.learningmaterial.system.dto;



public record SourceDraft(
        int chunkNo,
        String content,
        String extractionMethod,
        String qualityNote,
        Integer pageNo) {
    public SourceDraft(
            int chunkNo, String content, String extractionMethod, String qualityNote) {
        this(chunkNo, content, extractionMethod, qualityNote, null);
    }
}
