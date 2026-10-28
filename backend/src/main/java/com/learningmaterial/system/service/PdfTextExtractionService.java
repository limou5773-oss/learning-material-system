package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.PdfExtraction;
import com.learningmaterial.system.dto.TaskLease;

/** PdfTextExtractionService 的业务契约。 */
public interface PdfTextExtractionService {
    PdfExtraction extract(TaskLease lease);

}
