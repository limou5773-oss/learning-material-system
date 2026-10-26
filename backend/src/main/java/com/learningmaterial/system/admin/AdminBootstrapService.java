package com.learningmaterial.system.admin;

import com.learningmaterial.system.auth.UserMapper;
import com.learningmaterial.system.auth.UserRow;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

@Service
public class AdminBootstrapService {
    private final AdminUserMapper admin;
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public AdminBootstrapService(AdminUserMapper admin, UserMapper users, PasswordEncoder passwords, Clock clock) {
        this.admin = admin;
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Transactional
    public void initialize(String username, String password, String displayName) {
        if (username == null || !username.matches("[A-Za-z0-9_]{6,32}") || password == null || password.isBlank()
            || password.length() < 8 || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72
            || displayName == null || displayName.trim().isEmpty() || displayName.length() > 64)
            throw new IllegalStateException("Invalid administrator bootstrap configuration");
        if (admin.lockControl() == null) throw new IllegalStateException("Administrator control row missing");
        String normalized = username.toLowerCase(Locale.ROOT);
        if (admin.countAdmins() > 0 || users.findByUsername(normalized) != null)
            throw new IllegalStateException("Administrator bootstrap refused");
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        UserRow user = new UserRow();
        user.setUsername(normalized);
        user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(displayName.trim());
        user.setRole("ADMIN");
        user.setStatus("ENABLED");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        users.insert(user);
    }
}
