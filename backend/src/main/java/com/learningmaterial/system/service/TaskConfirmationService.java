package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.AiTaskDtos.*;

/** TaskConfirmationService 的业务契约。 */
public interface TaskConfirmationService {
    ConfirmationResult confirm(long userId, String id, ConfirmationInput input);

}
