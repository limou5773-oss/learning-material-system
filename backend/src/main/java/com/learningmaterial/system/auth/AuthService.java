package com.learningmaterial.system.auth;

import com.learningmaterial.system.auth.AuthDtos.LoginInput;
import com.learningmaterial.system.auth.AuthDtos.RegistrationInput;
import com.learningmaterial.system.auth.AuthDtos.SessionToken;
import com.learningmaterial.system.auth.AuthDtos.UserView;
import com.learningmaterial.system.common.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class AuthService {
    private static final Duration SESSION_LIFETIME = Duration.ofDays(7);
    private final UserMapper users;
    private final SessionMapper sessions;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(UserMapper users, SessionMapper sessions, PasswordEncoder passwords, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.clock = clock;
        this.dummyHash = passwords.encode("unknown-user-" + random.nextLong());
    }

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

    @Transactional
    public SessionToken login(LoginInput input) {
        UserRow user = users.findByUsernameForUpdate(input.username().trim().toLowerCase(Locale.ROOT));
        boolean matches = passwords.matches(input.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches || !"ENABLED".equals(user.getStatus())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiry = clock.instant().plus(SESSION_LIFETIME);
        LocalDateTime now = utcNow();
        SessionRow session = new SessionRow();
        session.setUserId(user.getId());
        session.setTokenHash(tokenHash(token));
        session.setClientType(input.clientType().name());
        session.setExpiresAt(LocalDateTime.ofInstant(expiry, ZoneOffset.UTC));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessions.insert(session);
        return new SessionToken(token, "Bearer", expiry, UserView.from(user));
    }

    @Transactional(readOnly = true)
    public AuthPrincipal authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        SessionRow session = sessions.findActive(tokenHash(token), utcNow());
        if (session == null) return null;
        UserRow user = users.findById(session.getUserId());
        if (user == null || !"ENABLED".equals(user.getStatus())) return null;
        return new AuthPrincipal(user.getId(), session.getId(), user.getRole());
    }

    @Transactional(readOnly = true)
    public UserView currentUser(AuthPrincipal principal) {
        UserRow user = users.findById(principal.userId());
        if (user == null || !"ENABLED".equals(user.getStatus())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请重新登录");
        }
        return UserView.from(user);
    }

    @Transactional
    public void logout(AuthPrincipal principal) {
        sessions.revoke(principal.sessionId(), utcNow());
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
