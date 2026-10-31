package com.learningmaterial.system.service.impl;

import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.AuthDtos.LoginInput;
import com.learningmaterial.system.dto.AuthDtos.RegistrationInput;
import com.learningmaterial.system.dto.AuthDtos.SessionToken;
import com.learningmaterial.system.dto.AuthDtos.UserView;
import com.learningmaterial.system.entity.SessionRow;
import com.learningmaterial.system.entity.UserRow;
import com.learningmaterial.system.entity.WechatBindingRow;
import com.learningmaterial.system.dto.UserPatch;
import com.learningmaterial.system.dto.WechatIdentity;
import com.learningmaterial.system.integration.wechat.WechatLoginClient;
import com.learningmaterial.system.mapper.WechatBindingMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.learningmaterial.system.mapper.SessionMapper;
import com.learningmaterial.system.mapper.UserMapper;
import com.learningmaterial.system.security.AuthPrincipal;
import com.learningmaterial.system.security.JwtTokenProvider;
import io.jsonwebtoken.JwtException;
import com.learningmaterial.system.service.AuthService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {
    private final UserMapper users;
    private final SessionMapper sessions;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final WechatBindingMapper bindings;
    private final WechatLoginClient wechat;
    private final TransactionTemplate transactions;
    private final JwtTokenProvider jwt;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthServiceImpl(UserMapper users, SessionMapper sessions, PasswordEncoder passwords, Clock clock,
                           WechatBindingMapper bindings, WechatLoginClient wechat, PlatformTransactionManager manager,
                           JwtTokenProvider jwt) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.clock = clock;
        this.bindings = bindings;
        this.wechat = wechat;
        this.transactions = new TransactionTemplate(manager);
        this.jwt = jwt;
        this.dummyHash = passwords.encode("unknown-user-" + random.nextLong());
    }

    @Override
    @Transactional
    public UserView register(RegistrationInput input) {
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PASSWORD", "密码的 UTF-8 编码不能超过 72 字节");
        }
        String displayName = input.displayName().trim();
        if (displayName.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DISPLAY_NAME", "显示名称不能为空");
        }
        LocalDateTime now = utcNow();
        UserRow user = new UserRow();
        user.setUsername(input.username().toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwords.encode(input.password()));
        user.setDisplayName(displayName);
        user.setRole("USER");
        user.setStatus("ENABLED");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            users.insert(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已被使用");
        }
        return UserView.from(user);
    }

    @Override
    @Transactional
    public SessionToken login(LoginInput input) {
        UserRow user = users.findByUsernameForUpdate(input.username().trim().toLowerCase(Locale.ROOT));
        boolean matches = passwords.matches(input.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches || !"ENABLED".equals(user.getStatus())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }
        return createSession(user, input.clientType());
    }

    private SessionToken createSession(UserRow user, com.learningmaterial.system.dto.AuthDtos.ClientType clientType) {
        var issued = jwt.issue(user.getId());
        String token = issued.token();
        Instant expiry = issued.expiresAt();
        LocalDateTime now = utcNow();
        SessionRow session = new SessionRow();
        session.setUserId(user.getId());
        session.setTokenHash(tokenHash(token));
        session.setClientType(clientType.name());
        session.setExpiresAt(LocalDateTime.ofInstant(expiry, ZoneOffset.UTC));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessions.insert(session);
        return new SessionToken(token, "Bearer", expiry, UserView.from(user));
    }

    @Override
    @Transactional(readOnly = true)
    public AuthPrincipal authenticate(String token) {
        long userId;
        try { userId = jwt.verify(token); }
        catch (JwtException | IllegalArgumentException invalidToken) { return null; }
        SessionRow session = sessions.findActive(tokenHash(token), utcNow());
        if (session == null || session.getUserId() != userId) return null;
        UserRow user = users.findById(session.getUserId());
        if (user == null || !"ENABLED".equals(user.getStatus())) return null;
        return new AuthPrincipal(user.getId(), session.getId(), user.getRole());
    }

    @Override
    @Transactional(readOnly = true)
    public UserView currentUser(AuthPrincipal principal) {
        UserRow user = users.findById(principal.userId());
        if (user == null || !"ENABLED".equals(user.getStatus())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请重新登录");
        }
        return UserView.from(user);
    }

    @Override
    @Transactional
    public void logout(AuthPrincipal principal) {
        sessions.revoke(principal.sessionId(), utcNow());
    }

    @Override
    @Transactional
    public UserView updateProfile(AuthPrincipal principal, UserPatch input) {
        if (input.displayName() == null && input.newPassword() == null)
            throw invalidProfile("请提供昵称或新密码");
        if (input.newPassword() == null && input.currentPassword() != null)
            throw invalidProfile("修改密码时请同时提供新密码");
        UserRow user = requireActiveUser(principal);
        String displayName = input.displayName() == null ? user.getDisplayName() : input.displayName();
        String hash = null;
        if (input.newPassword() != null) {
            requirePassword(user, input.currentPassword());
            if (input.newPassword().isBlank()) throw invalidProfile("新密码不能为空白");
            if (input.newPassword().getBytes(StandardCharsets.UTF_8).length > 72)
                throw invalidProfile("密码的 UTF-8 编码不能超过 72 字节");
            hash = passwords.encode(input.newPassword());
        }
        LocalDateTime now = utcNow();
        users.updateProfile(user.getId(), displayName, hash, now);
        if (hash != null) sessions.revokeAll(user.getId(), now);
        user.setDisplayName(displayName);
        user.setUpdatedAt(now);
        return UserView.from(user);
    }

    @Override
    public SessionToken loginWechat(String code) {
        // Temporary-code exchange performs network I/O before acquiring database locks.
        WechatIdentity identity = wechat.exchange(code);
        WechatBindingRow snapshot = bindings.findByIdentity(identity.appId(), identity.openId());
        if (snapshot == null) throw bindingRequired();
        return transactions.execute(status -> {
            UserRow user = users.findByIdForUpdate(snapshot.userId());
            if (user == null || !"ENABLED".equals(user.getStatus()))
                throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号不可用，请使用账号密码登录");
            WechatBindingRow current = bindings.findByUserForUpdate(user.getId());
            if (current == null || !sameIdentity(current, identity)) throw bindingRequired();
            user.setWechatBound(true);
            return createSession(user, com.learningmaterial.system.dto.AuthDtos.ClientType.MINIPROGRAM);
        });
    }

    @Override
    public UserView bindWechat(AuthPrincipal principal, String code) {
        WechatIdentity identity = wechat.exchange(code);
        try {
            return transactions.execute(status -> {
                UserRow user = requireActiveUser(principal);
                WechatBindingRow existing = bindings.findByUserForUpdate(user.getId());
                if (existing != null && !sameIdentity(existing, identity))
                    throw new ApiException(HttpStatus.CONFLICT, "USER_WECHAT_ALREADY_BOUND", "该账号已绑定其他微信，请先验证密码后解绑");
                if (existing == null) bindings.insert(user.getId(), identity.appId(), identity.openId(), utcNow());
                user.setWechatBound(true);
                return UserView.from(user);
            });
        } catch (DuplicateKeyException ex) {
            // Only the two declared binding uniqueness constraints map to a conflict.
            throw new ApiException(HttpStatus.CONFLICT, "WECHAT_BIND_CONFLICT", "该微信已绑定其他账号，不会自动合并账号");
        }
    }

    @Override
    @Transactional
    public void unbindWechat(AuthPrincipal principal, String currentPassword) {
        UserRow user = requireActiveUser(principal);
        requirePassword(user, currentPassword);
        bindings.deleteByUser(user.getId());
        sessions.revokeMiniprogram(user.getId(), utcNow());
    }

    private UserRow requireActiveUser(AuthPrincipal principal) {
        // Serialize sensitive changes with account disable/login before checking the current session.
        UserRow user = users.findByIdForUpdate(principal.userId());
        if (user == null || !"ENABLED".equals(user.getStatus())
                || sessions.findActiveById(principal.sessionId(), principal.userId(), utcNow()) == null)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录已失效，请重新登录");
        return user;
    }

    private void requirePassword(UserRow user, String password) {
        if (password == null || password.isBlank() || !passwords.matches(password, user.getPasswordHash()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENT_PASSWORD_INCORRECT", "原密码不正确");
    }

    private static boolean sameIdentity(WechatBindingRow binding, WechatIdentity identity) {
        return binding.appId().equals(identity.appId()) && binding.openId().equals(identity.openId());
    }

    private static ApiException bindingRequired() {
        return new ApiException(HttpStatus.CONFLICT, "WECHAT_BIND_REQUIRED", "该微信尚未绑定，请先用已有账号密码登录，再到我的信息中绑定");
    }

    private static ApiException invalidProfile(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", message);
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
