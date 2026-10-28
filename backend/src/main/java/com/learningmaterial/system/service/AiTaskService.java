package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.AiTaskDtos.RetryInput;
import com.learningmaterial.system.dto.AiTaskDtos.TaskInput;
import com.learningmaterial.system.dto.AiTaskDtos.TaskStepView;
import com.learningmaterial.system.dto.AiTaskDtos.TaskView;
import com.learningmaterial.system.dto.MaterialDtos.PageView;

/** AiTaskService 的业务契约。 */
public interface AiTaskService {
    TaskView submit(long userId, TaskInput input);

    TaskView get(long userId, String id);

    TaskView cancel(long userId, String id);

    TaskView retry(long userId, String id, RetryInput input);

    PageView<TaskStepView> steps(long userId, String id, int page, int pageSize);

    PageView<TaskView> list(long userId, String status, int page, int pageSize);

}
