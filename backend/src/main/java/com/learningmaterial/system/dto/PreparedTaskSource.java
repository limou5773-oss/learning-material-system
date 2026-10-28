package com.learningmaterial.system.dto;

import com.learningmaterial.system.entity.MaterialChunkRow;
import java.util.List;

public record PreparedTaskSource(
        List<MaterialChunkRow> sources, PdfExtraction extraction) {}
