package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.OcrExtractionOutcome;
import com.learningmaterial.system.dto.PdfExtraction;
import com.learningmaterial.system.dto.TaskLease;

/** OcrExtractionService 的业务契约。 */
public interface OcrExtractionService {
    String attemptSummary(TaskLease lease);

    OcrExtractionOutcome extract(TaskLease lease, PdfExtraction pdf);

}
