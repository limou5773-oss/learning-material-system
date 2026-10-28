package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.AiTaskDtos.*;
import com.learningmaterial.system.dto.PersonalMaterialMatch;
import com.learningmaterial.system.dto.PreparedTaskSource;
import com.learningmaterial.system.dto.TaskLease;
import com.learningmaterial.system.entity.AiTaskRow;
import com.learningmaterial.system.task.AgentPlanProtocol;
import java.util.List;
import java.util.function.Consumer;

/** ModelGenerationService 的业务契约。 */
public interface ModelGenerationService {
    public static final String PROMPT_VERSION = "d15-v1";

    boolean check(TaskLease lease, AiTaskRow snapshot);

    void generate(
            TaskLease lease,
            AiTaskRow snapshot,
            PreparedTaskSource prepared,
            Consumer<CandidateResult> publication);

    void generateAgent(
            TaskLease lease,
            AiTaskRow snapshot,
            PreparedTaskSource prepared,
            List<PersonalMaterialMatch> matches,
            Consumer<CandidateResult> staging);

    AgentPlanProtocol.Plan plan(
            TaskLease lease,
            AiTaskRow snapshot,
            String input,
            List<PersonalMaterialMatch> matches);

    boolean publishAgent(
            TaskLease lease,
            AiTaskRow snapshot,
            List<PersonalMaterialMatch> matches,
            Runnable publication);

}
