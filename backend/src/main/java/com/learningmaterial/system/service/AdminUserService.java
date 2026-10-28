package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.AuthDtos.UserView;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.security.AuthPrincipal;

/** AdminUserService 的业务契约。 */
public interface AdminUserService {
    PageView<UserView> list(String keyword, String status, int page, int pageSize);

    UserView changeStatus(AuthPrincipal principal, long targetId, String status, String reason);

}
