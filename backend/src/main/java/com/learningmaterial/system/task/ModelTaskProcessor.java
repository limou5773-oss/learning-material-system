package com.learningmaterial.system.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lms.task.processor.mode", havingValue = "model")
public class ModelTaskProcessor implements TaskProcessor {
    private final AiTaskMapper tasks;
    private final TaskLeaseService leases;
    private final TaskSourcePreparation preparation;
    private final ModelGenerationService generation;
    private final TaskCandidateService candidates;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public ModelTaskProcessor(
            AiTaskMapper tasks,
            TaskLeaseService leases,
            TaskSourcePreparation preparation,
            ModelGenerationService generation,
            TaskCandidateService candidates,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.tasks = tasks;
        this.leases = leases;
        this.preparation = preparation;
        this.generation = generation;
        this.candidates = candidates;
        this.json = json;
    }

    public void execute(TaskLeaseService.Lease lease) {
        var task = tasks.findById(lease.taskId());
        if (task == null || !generation.check(lease, task)) return;
        com.fasterxml.jackson.databind.JsonNode config;
        try {
            config = json.readTree(task.getConfigJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new TaskExecutionException("INVALID_TASK_CONFIG");
        }
        var prepared = preparation.prepare(lease, task, config, true);
        if (prepared == null) return;
        Long call = leases.startStep(lease, "generate_summary_cards", "MODEL");
        if (call == null) return;
        try {
            generation.generate(
                    lease,
                    task,
                    prepared,
                    result -> {
                        if (!leases.completeStep(
                                lease, call, true, result.cards().size() + "张模型候选卡片；请人工核对", null))
                            throw new TaskExecutionException("LEASE_LOST");
                        Long validate = leases.startStep(lease, "validate_candidate", "LOCAL");
                        if (validate == null
                                || !leases.completeStep(
                                        lease, validate, true, "已核验结构和引用原文；内容仍需人工核对", null)
                                || !candidates.finish(lease, result))
                            throw new TaskExecutionException("LEASE_LOST");
                    });
        } catch (TaskExecutionException ex) {
            leases.completeStep(lease, call, false, "模型生成未完成；未保存正式卡片", ex.errorCode());
            throw ex;
        }
    }
}
