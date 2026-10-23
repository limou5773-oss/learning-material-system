package com.learningmaterial.system.task;

public interface TaskProcessor {
    void execute(TaskLeaseService.Lease lease);
}
