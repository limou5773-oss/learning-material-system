package com.learningmaterial.system.admin;

import com.learningmaterial.system.auth.AuthDtos.UserView;
import com.learningmaterial.system.auth.AuthPrincipal;
import com.learningmaterial.system.auth.SessionMapper;
import com.learningmaterial.system.auth.UserMapper;
import com.learningmaterial.system.auth.UserRow;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class AdminUserService {
    private final AdminUserMapper admin;
    private final UserMapper users;
    private final SessionMapper sessions;
    private final Clock clock;

    public AdminUserService(AdminUserMapper admin, UserMapper users, SessionMapper sessions, Clock clock) {
        this.admin = admin;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageView<UserView> list(String keyword, String status, int page, int pageSize) {
        keyword = blankToNull(keyword);
        status = blankToNull(status);
        if (keyword != null && keyword.length() > 100 || page < 1 || pageSize < 1 || pageSize > 100
            || status != null && !validStatus(status)) throw invalid();
        String escaped = keyword == null ? null : keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return new PageView<>(admin.list(escaped, status, (long) (page - 1) * pageSize, pageSize)
            .stream().map(AdminUserService::view).toList(), page, pageSize, admin.count(escaped, status));
    }

    @Transactional
    public UserView changeStatus(AuthPrincipal principal, long targetId, String status, String reason) {
        if (!validStatus(status) || reason == null || reason.trim().isEmpty() || reason.length() > 1000) throw invalid();
        if (admin.lockControl() == null) throw new IllegalStateException("Administrator control row missing");
        UserRow first = users.findByIdForUpdate(Math.min(principal.userId(), targetId));
        UserRow second = targetId == principal.userId() ? first : users.findByIdForUpdate(Math.max(principal.userId(), targetId));
        UserRow actor = principal.userId() <= targetId ? first : second;
        UserRow target = targetId <= principal.userId() ? first : second;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (actor == null || !"ENABLED".equals(actor.getStatus())) throw unauthorized();
        if (!"ADMIN".equals(actor.getRole())) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有访问权限");
        if (sessions.findActiveById(principal.sessionId(), principal.userId(), now) == null) throw unauthorized();
        if (target == null) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        if ("DISABLED".equals(status)) {
            if (targetId == principal.userId()) throw new ApiException(HttpStatus.CONFLICT, "SELF_DISABLE_FORBIDDEN", "不能停用当前账号");
            if ("ADMIN".equals(target.getRole()) && "ENABLED".equals(target.getStatus()) && admin.countEnabledAdmins() <= 1)
                throw new ApiException(HttpStatus.CONFLICT, "LAST_ENABLED_ADMIN", "不能停用最后一个启用的管理员");
        }
        String before = target.getStatus();
        admin.updateStatus(targetId, status, now);
        if ("DISABLED".equals(status)) sessions.revokeAll(targetId, now);
        admin.audit(principal.userId(), targetId, reason.trim(), before, status, now);
        target.setStatus(status);
        return view(target);
    }

    static UserView view(UserRow row) {
        return new UserView(row.getId().toString(), row.getUsername(), row.getDisplayName(), row.getRole(), row.getStatus(), false,
            row.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
    private static String blankToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private static boolean validStatus(String value) { return "ENABLED".equals(value) || "DISABLED".equals(value); }
    private static ApiException invalid() { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", "请检查输入字段"); }
    private static ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请重新登录"); }
}
