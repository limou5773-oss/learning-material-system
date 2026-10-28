package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.AiTaskDtos.CandidateResult;
import com.learningmaterial.system.dto.AiTaskDtos.SourceView;
import com.learningmaterial.system.dto.SourceDraft;
import com.learningmaterial.system.dto.TaskLease;
import com.learningmaterial.system.entity.MaterialChunkRow;
import java.util.List;

/** TaskCandidateService 的业务契约。 */
public interface TaskCandidateService {
    public static final int MOCK_METADATA_CHUNK_NO = 1_000_000;

    public static final int PDF_CHUNK_BASE = 2_000_000;

    public static final int OCR_CHUNK_BASE = 3_000_000;

    public static final int MODEL_NOTE_CHUNK_BASE = 4_000_000;

    List<MaterialChunkRow> saveSources(
            TaskLease lease, List<SourceDraft> drafts);

    boolean finish(TaskLease lease, CandidateResult candidate);

    CandidateResult getResult(long userId, long taskId);

    List<SourceView> getSources(long userId, long taskId);

}
