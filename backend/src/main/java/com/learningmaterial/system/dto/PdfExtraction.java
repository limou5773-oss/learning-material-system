package com.learningmaterial.system.dto;

import com.learningmaterial.system.entity.MaterialChunkRow;
import java.util.List;

public record PdfExtraction(List<MaterialChunkRow> sources, int totalPages,
                         List<Integer> processedPages, List<String> warnings, List<Integer> needsOcrPages) {
    public PdfExtraction(List<MaterialChunkRow> sources,int totalPages,List<Integer> processedPages,List<String> warnings) {
        this(sources,totalPages,processedPages,warnings,List.of());
    }
}
