package com.learningmaterial.system.dto;



public record CardSourceView(String chunkId, String materialId, String materialTitle,
                             int materialVersion, Integer pageNo, String quoteText, String content,
                             boolean isAvailable, String unavailableReason, String extractionMethod, String qualityNote) { }
