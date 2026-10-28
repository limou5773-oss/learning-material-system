package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.TaskLease;

/** TaskLeaseService 的业务契约。 */
public interface TaskLeaseService {
    public static final int LEASE_SECONDS = 30;

    public static final int EXECUTION_SECONDS = 300;

    TaskLease claim();

    boolean isCurrent(TaskLease lease);

    boolean heartbeat(TaskLease lease);

    boolean saveProgress(TaskLease lease, String stage, String contextJson);

    Long startStep(TaskLease lease, String toolName, String providerKind);

    boolean completeStep(TaskLease lease, long callId, boolean success, String summary, String errorCode);

    boolean fail(TaskLease lease, String errorCode);

}
