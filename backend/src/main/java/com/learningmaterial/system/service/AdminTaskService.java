package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.AdminTaskDtos.AdminTask;
import com.learningmaterial.system.dto.AdminTaskDtos.AdminTaskStep;
import com.learningmaterial.system.dto.MaterialDtos.PageView;

/** AdminTaskService 的业务契约。 */
public interface AdminTaskService {
    PageView<AdminTask> list(String status, String rawFrom, String rawTo, int page, int pageSize);

    PageView<AdminTaskStep> steps(String rawId, int page, int pageSize);

}
