package com.learningmaterial.system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.learningmaterial.system.dto.PreparedTaskSource;
import com.learningmaterial.system.dto.TaskLease;
import com.learningmaterial.system.entity.AiTaskRow;

/** TaskSourcePreparationService 的业务契约。 */
public interface TaskSourcePreparationService {
    PreparedTaskSource prepare(
            TaskLease lease, AiTaskRow task, JsonNode config, boolean model);

    PreparedTaskSource prepareAgent(
            TaskLease lease, JsonNode config, String tool, PreparedTaskSource prior);

}
