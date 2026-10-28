package com.learningmaterial.system.task;

import com.learningmaterial.system.dto.TaskLease;

public interface TaskProcessor {
    void execute(TaskLease lease);
}
