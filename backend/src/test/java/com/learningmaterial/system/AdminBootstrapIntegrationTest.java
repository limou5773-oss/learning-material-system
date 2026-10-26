package com.learningmaterial.system;

import com.learningmaterial.system.admin.AdminBootstrapRunner;
import com.learningmaterial.system.admin.AdminBootstrapService;
import com.learningmaterial.system.auth.AuthDtos;
import com.learningmaterial.system.auth.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class AdminBootstrapIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private AdminBootstrapService bootstrap;
    @Autowired private AuthService auth;
    private final List<String> usernames = new ArrayList<>();

    @AfterEach
    void clean() {
        for (String username : usernames) {
            jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM app_user WHERE username=?)", username);
            jdbc.update("DELETE FROM app_user WHERE username=?", username);
        }
    }

    @Test
    void startupIsDisabledUnlessExplicitlyEnabled() {
        assertThat(context.getEnvironment().getProperty("lms.admin-bootstrap.enabled", Boolean.class, false)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role='ADMIN'", Integer.class)).isZero();
        assertThat(context.containsBean("adminBootstrapRunner")).isTrue();
        assertThat(context.getBean("adminBootstrapRunner").toString()).doesNotContain("password=");
    }

    @Test
    void explicitInitializationCreatesExactlyOneNormalizedBcryptAdmin() {
        String username = username(), password = password();
        AdminBootstrapRunner runner = new AdminBootstrapRunner(bootstrap, true, username.toUpperCase(), password, "  首位管理员  ");
        runner.run(new DefaultApplicationArguments());
        assertThat(runner.toString()).doesNotContain(password);
        var row = jdbc.queryForMap("SELECT username,password_hash,display_name,role,status FROM app_user WHERE username=?", username);
        assertThat(row).containsEntry("username", username).containsEntry("display_name", "首位管理员").containsEntry("role", "ADMIN").containsEntry("status", "ENABLED");
        String hash = row.get("password_hash").toString();
        assertThat(hash).startsWith("$2").isNotEqualTo(password);
        assertThat(passwords.matches(password, hash)).isTrue();
        var loggedIn = auth.login(new AuthDtos.LoginInput(username, password, AuthDtos.ClientType.WEB));
        assertThat(loggedIn.user().role()).isEqualTo("ADMIN");
        assertThat(auth.authenticate(loggedIn.accessToken())).isNotNull();
        assertThatThrownBy(() -> initialize(username, password(), "重新初始化")).isInstanceOf(IllegalStateException.class).hasMessage("Administrator bootstrap refused");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username=?", String.class, username)).isEqualTo(hash);
    }

    @Test
    void existingDisabledAdminAlsoBlocksInitializationWithoutChangingEitherAccount() {
        String existing = username();
        initialize(existing, password(), "已有管理员");
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE username=?", existing);
        String requested = username();
        assertThatThrownBy(() -> initialize(requested, password(), "不得新增")).isInstanceOf(IllegalStateException.class).hasMessage("Administrator bootstrap refused");
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE username=?", String.class, existing)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?", Integer.class, requested)).isZero();
    }

    @Test
    void occupiedOrdinaryUsernameIsNeverPromotedOrReset() {
        String username = username(), hash = passwords.encode(password());
        jdbc.update("INSERT INTO app_user(username,password_hash,display_name,role,status,created_at,updated_at) VALUES(?,?,?,'USER','ENABLED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", username, hash, "普通用户");
        assertThatThrownBy(() -> initialize(username, password(), "不得提权")).isInstanceOf(IllegalStateException.class).hasMessage("Administrator bootstrap refused");
        assertThat(jdbc.queryForMap("SELECT password_hash,display_name,role,status FROM app_user WHERE username=?", username))
            .containsEntry("password_hash", hash).containsEntry("display_name", "普通用户").containsEntry("role", "USER").containsEntry("status", "ENABLED");
    }

    @Test
    void invalidConfigurationFailsGenericallyWithoutEchoingAnyPassword() {
        String username = username();
        for (String password : List.of("short", " ".repeat(10), "密".repeat(30), "x".repeat(65))) {
            assertThatThrownBy(() -> initialize(username, password, "管理员")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid administrator bootstrap configuration").hasMessageNotContaining(password).hasNoCause();
        }
        for (String invalidName : List.of("x", "invalid-name", "x".repeat(33))) {
            assertThatThrownBy(() -> initialize(invalidName, password(), "管理员")).hasMessage("Invalid administrator bootstrap configuration");
        }
        assertThatThrownBy(() -> initialize(username, password(), " ")).hasMessage("Invalid administrator bootstrap configuration");
        assertThatThrownBy(() -> initialize(username, password(), "名".repeat(65))).hasMessage("Invalid administrator bootstrap configuration");
        assertThatThrownBy(() -> initialize(null, null, null)).hasMessage("Invalid administrator bootstrap configuration");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?", Integer.class, username)).isZero();
    }

    @Test
    void concurrentInitializersCreateAtMostOneAdmin() throws Exception {
        String first = username(), second = username();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        var a = CompletableFuture.supplyAsync(() -> attempt(first, ready, start));
        var b = CompletableFuture.supplyAsync(() -> attempt(second, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role='ADMIN'", Integer.class)).isEqualTo(1);
    }

    private boolean attempt(String username, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Initializer start barrier timed out");
            initialize(username, password(), "并发管理员");
            return true;
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("Administrator bootstrap refused");
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void initialize(String username, String password, String displayName) {
        bootstrap.initialize(username, password, displayName);
    }
    private String username() {
        String name = "boot_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        usernames.add(name);
        return name;
    }
    private static String password() { return "Safe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
}
