package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.admin.AdminUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class AdminUserIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private DataSource dataSource;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private PasswordEncoder passwords;
    @MockitoSpyBean private AdminUserMapper adminMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> ids = new ArrayList<>();
    private Account admin, user;

    @BeforeEach
    void setup() throws Exception {
        admin = register("Admin");
        // The production bootstrap is tested separately; this fixture isolates management HTTP behavior.
        jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?", admin.id());
        admin = admin.withToken(login(admin, "WEB"));
        user = register("User");
        user = user.withToken(login(user, "WEB"));
    }

    @AfterEach
    void clean() {
        reset(passwords, adminMapper);
        for (String trigger : List.of("d21_fail_user", "d21_fail_session", "d21_fail_audit")) {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
        boolean auditExists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='review_record'", Integer.class) > 0;
        for (long id : ids) {
            if (auditExists) jdbc.update("DELETE FROM review_record WHERE operator_id=? OR target_user_id=?", id, id);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", id);
        }
        for (long id : ids) jdbc.update("DELETE FROM app_user WHERE id=?", id);
        ids.clear();
    }

    @Test
    void adminBoundaryRejectsAnonymousAndOrdinaryUsersBeforeReadingData() throws Exception {
        assertThat(request("GET", "/admin/users", null, null).status()).isEqualTo(401);
        assertThat(request("GET", "/admin/users", null, user.token()).status()).isEqualTo(403);
        assertThat(patch(user.id(), "DISABLED", "无权限", user.token()).status()).isEqualTo(403);
        assertThat(patch(user.id(), "DISABLED", "未登录", null).status()).isEqualTo(401);
    }

    @Test
    void pageExposesExactlySevenSafeFieldsAndLiteralKeywordMatches() throws Exception {
        Account literal = register("literal_%\\q");
        Account unrelated = register("literalABq");
        String query = "?keyword=" + enc("  literal_%\\q  ");
        Reply response = request("GET", "/admin/users" + query, null, admin.token());
        assertThat(response.status()).isEqualTo(200);
        JsonNode data = response.body().path("data");
        assertThat(data.path("total").asInt()).isEqualTo(1);
        JsonNode view = data.path("items").get(0);
        assertThat(view.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "username", "displayName", "role", "status", "hasWechatBinding", "createdAt");
        assertThat(view.path("id").isTextual()).isTrue();
        assertThat(view.path("id").asText()).isEqualTo(Long.toString(literal.id()));
        assertThat(view.path("createdAt").asText()).endsWith("Z");
        assertThat(view.path("hasWechatBinding").asBoolean()).isFalse();
        assertThat(response.body().toString()).doesNotContain("passwordHash", "password_hash", "tokenHash", "openid", user.password());
        assertThat(unrelated.id()).isNotEqualTo(literal.id());
    }

    @Test
    void paginationStatusOrderingAndBoundsAreExplicit() throws Exception {
        String key = "page_" + UUID.randomUUID().toString().substring(0, 8);
        Account first = register(key), second = register(key), third = register(key);
        jdbc.update("UPDATE app_user SET created_at='2025-01-01 00:00:00' WHERE id IN (?,?,?)", first.id(), second.id(), third.id());
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?", first.id());
        Reply page = request("GET", "/admin/users?keyword=" + enc(key) + "&page=2&pageSize=1", null, admin.token());
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().path("data").path("items").get(0).path("id").asLong()).isEqualTo(second.id());
        assertThat(page.body().path("data").path("total").asInt()).isEqualTo(3);
        Reply disabled = request("GET", "/admin/users?keyword=" + enc(key) + "&status=DISABLED", null, admin.token());
        assertThat(disabled.body().path("data").path("total").asInt()).isEqualTo(1);
        assertThat(request("GET", "/admin/users?page=99&pageSize=100&keyword=" + enc(key), null, admin.token()).body().path("data").path("items")).isEmpty();
        for (String query : List.of("page=0", "page=-1", "pageSize=0", "pageSize=101", "status=UNKNOWN", "keyword=" + "x".repeat(101))) {
            assertThat(request("GET", "/admin/users?" + query, null, admin.token()).status()).as(query).isEqualTo(422);
        }
        for (String query : List.of("page=x", "pageSize=1.1")) {
            assertThat(request("GET", "/admin/users?" + query, null, admin.token()).status()).as(query).isEqualTo(400);
        }
        assertThat(request("GET", "/admin/users?status=%20%20&keyword=%20%20", null, admin.token()).status()).isEqualTo(200);
    }

    @Test
    void statusInputRequiresOneStrictJsonObjectAndMeaningfulReason() throws Exception {
        String path = "/admin/users/" + user.id() + "/status";
        for (String body : List.of("[]", "null", "1", "\"x\"", "{", "{\"status\":\"DISABLED\",\"reason\":\"a\",\"role\":\"ADMIN\"}", "{\"status\":\"DISABLED\",\"reason\":\"a\"} {}", "{\"status\":1,\"reason\":\"a\"}", "{\"status\":\"DISABLED\",\"reason\":1}")) {
            assertThat(raw("PATCH", path, body, admin.token()).status()).as(body).isEqualTo(400);
        }
        for (String body : List.of("{}", "{\"status\":\"DISABLED\"}", "{\"status\":null,\"reason\":\"a\"}", "{\"status\":\"UNKNOWN\",\"reason\":\"a\"}", "{\"status\":\"DISABLED\",\"reason\":null}", "{\"status\":\"DISABLED\",\"reason\":\"  \\n \"}")) {
            assertThat(raw("PATCH", path, body, admin.token()).status()).as(body).isEqualTo(422);
        }
        assertThat(patch(user.id(), "DISABLED", "理".repeat(1001), admin.token()).status()).isEqualTo(422);
        assertThat(patch(user.id(), "ENABLED", "理".repeat(1000), admin.token()).status()).isEqualTo(200);
        for (String id : List.of("0", "-1", "abc", "999999999999999999999999999", Long.toString(Long.MAX_VALUE), "+" + user.id(), "0" + user.id())) {
            assertThat(request("PATCH", "/admin/users/" + id + "/status", Map.of("status", "DISABLED", "reason", "目标"), admin.token()).status()).as(id).isEqualTo(404);
        }
    }

    @Test
    void disablingRevokesBothClientsAndEnablingNeverRevivesOldSessions() throws Exception {
        String mini = login(user, "MINIPROGRAM");
        Map<String,Object> unchanged = jdbc.queryForMap("SELECT username,password_hash,role,created_at FROM app_user WHERE id=?", user.id());
        Reply disabled = patch(user.id(), "DISABLED", "  管理原因  ", admin.token());
        assertThat(disabled.status()).isEqualTo(200);
        assertThat(disabled.body().path("data").path("status").asText()).isEqualTo("DISABLED");
        assertThat(request("GET", "/users/me", null, user.token()).status()).isEqualTo(401);
        assertThat(request("GET", "/users/me", null, mini).status()).isEqualTo(401);
        assertThat(request("POST", "/auth/sessions", Map.of("username", user.username(), "password", user.password(), "clientType", "WEB"), null).status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id=? AND revoked_at IS NULL", Integer.class, user.id())).isZero();
        assertThat(jdbc.queryForMap("SELECT username,password_hash,role,created_at FROM app_user WHERE id=?", user.id())).isEqualTo(unchanged);
        assertThat(jdbc.queryForMap("SELECT operator_id,target_user_id,action,reason,before_status,after_status FROM review_record WHERE target_user_id=?", user.id()))
            .containsEntry("operator_id", admin.id()).containsEntry("target_user_id", user.id()).containsEntry("action", "USER_STATUS")
            .containsEntry("reason", "管理原因").containsEntry("before_status", "ENABLED").containsEntry("after_status", "DISABLED");
        assertThat(patch(user.id(), "ENABLED", "恢复", admin.token()).status()).isEqualTo(200);
        assertThat(request("GET", "/users/me", null, user.token()).status()).isEqualTo(401);
        assertThat(request("GET", "/users/me", null, mini).status()).isEqualTo(401);
        assertThat(request("GET", "/users/me", null, login(user, "WEB")).status()).isEqualTo(200);
        assertThat(patch(user.id(), "ENABLED", "明确同值操作", admin.token()).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE target_user_id=?", Integer.class, user.id())).isEqualTo(3);
    }

    @Test
    void selfDisableAndLastEnabledAdminAreProtected() throws Exception {
        Reply self = patch(admin.id(), "DISABLED", "自停", admin.token());
        assertThat(self.status()).isEqualTo(409);
        assertThat(self.body().path("error").path("code").asText()).isIn("SELF_DISABLE_FORBIDDEN", "LAST_ENABLED_ADMIN");
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id=?", String.class, admin.id())).isEqualTo("ENABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE target_user_id=?", Integer.class, admin.id())).isZero();
    }

    @Test
    void userUpdateSessionRevocationAndAuditFailuresRollbackTheWholeOperation() throws Exception {
        for (String part : List.of("user", "session", "audit")) {
            String table = switch (part) { case "user" -> "app_user"; case "session" -> "auth_session"; default -> "review_record"; };
            String operation = part.equals("audit") ? "INSERT" : "UPDATE";
            String condition = part.equals("user") ? "NEW.id=" + user.id() : "NEW." + (part.equals("audit") ? "target_user_id" : "user_id") + "=" + user.id();
            jdbc.execute("CREATE TRIGGER d21_fail_" + part + " BEFORE " + operation + " ON " + table + " FOR EACH ROW BEGIN IF " + condition + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='D21 injected transaction failure'; END IF; END");
            try {
                assertThat(patch(user.id(), "DISABLED", "回滚", admin.token()).status()).as(part).isEqualTo(500);
                assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id=?", String.class, user.id())).isEqualTo("ENABLED");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id=? AND revoked_at IS NULL", Integer.class, user.id())).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE target_user_id=?", Integer.class, user.id())).isZero();
                assertThat(request("GET", "/users/me", null, user.token()).status()).isEqualTo(200);
            } finally { jdbc.execute("DROP TRIGGER d21_fail_" + part); }
        }
    }

    @Test
    void loginThatLocksFirstHasItsNewSessionRevokedByTheWaitingDisable() throws Exception {
        CountDownLatch passwordReached = new CountDownLatch(1), finishPassword = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (user.password().contentEquals(invocation.getArgument(0, CharSequence.class))) {
                passwordReached.countDown();
                if (!finishPassword.await(10, TimeUnit.SECONDS)) throw new AssertionError("Password barrier timed out");
            }
            return invocation.callRealMethod();
        }).when(passwords).matches(anyString(), anyString());
        CompletableFuture<Reply> login = async(() -> request("POST", "/auth/sessions",
            Map.of("username", user.username(), "password", user.password(), "clientType", "MINIPROGRAM"), null));
        try {
            assertThat(passwordReached.await(5, TimeUnit.SECONDS)).as("login reached password check while holding user row").isTrue();
            CompletableFuture<Reply> disabling = async(() -> patch(user.id(), "DISABLED", "并发停用", admin.token()));
            try {
                awaitWaiters("app_user", 1);
            } finally { finishPassword.countDown(); }
            Reply loggedIn = login.get(10, TimeUnit.SECONDS);
            assertThat(loggedIn.status()).isEqualTo(201);
            assertThat(disabling.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(request("GET", "/users/me", null, loggedIn.body().path("data").path("accessToken").asText()).status()).isEqualTo(401);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id=? AND revoked_at IS NULL", Integer.class, user.id())).isZero();
        } finally { finishPassword.countDown(); login.get(10, TimeUnit.SECONDS); }
    }

    @Test
    void disableThatLocksFirstMakesWaitingLoginReadCommittedDisabledStatus() throws Exception {
        CountDownLatch disablingReached = new CountDownLatch(1), finishDisable = new CountDownLatch(1);
        doAnswer(invocation -> {
            disablingReached.countDown();
            if (!finishDisable.await(10, TimeUnit.SECONDS)) throw new AssertionError("Disable barrier timed out");
            // A fresh MyBatis proxy executes the real mapping on the same transaction after the barrier.
            return sqlSession.getConfiguration().getMapper(AdminUserMapper.class, sqlSession)
                .updateStatus(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        }).when(adminMapper).updateStatus(eq(user.id()), anyString(), any(LocalDateTime.class));
        CompletableFuture<Reply> disabling = async(() -> patch(user.id(), "DISABLED", "先取得锁", admin.token()));
        try {
            assertThat(disablingReached.await(5, TimeUnit.SECONDS)).as("HTTP disable reached update with user locks held").isTrue();
            CompletableFuture<Reply> login = async(() -> request("POST", "/auth/sessions",
                Map.of("username", user.username(), "password", user.password(), "clientType", "MINIPROGRAM"), null));
            try { awaitWaiters("app_user", 1); }
            finally { finishDisable.countDown(); }
            assertThat(disabling.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(login.get(10, TimeUnit.SECONDS).status()).isEqualTo(401);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id=? AND revoked_at IS NULL", Integer.class, user.id())).isZero();
        } finally { finishDisable.countDown(); disabling.get(10, TimeUnit.SECONDS); }
    }

    @Test
    void twoAdminsTryingToDisableEachOtherLeaveOneEnabledAndRejectTheStaleActor() throws Exception {
        Account second = register("另一管理员");
        jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?", second.id());
        String secondToken = login(second, "WEB");
        CompletableFuture<Reply> first, other;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            execute(blocker, "SELECT id FROM admin_control_lock WHERE id=1 FOR UPDATE");
            first = async(() -> patch(second.id(), "DISABLED", "互停A", admin.token()));
            other = async(() -> patch(admin.id(), "DISABLED", "互停B", secondToken));
            try { awaitWaiters("admin_control_lock", 2); }
            finally { blocker.commit(); }
        }
        assertThat(List.of(first.get(10, TimeUnit.SECONDS).status(), other.get(10, TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(200, 401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id IN (?,?) AND role='ADMIN' AND status='ENABLED'", Integer.class, admin.id(), second.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE operator_id IN (?,?)", Integer.class, admin.id(), second.id())).isEqualTo(1);
    }

    @Test
    void waitingManagementWriteRechecksSessionAndCurrentRoleAfterTheFilter() throws Exception {
        for (String change : List.of("session", "role", "status")) {
            jdbc.update("UPDATE app_user SET role='ADMIN',status='ENABLED' WHERE id=?", admin.id());
            String token = login(admin, "WEB");
            CompletableFuture<Reply> pending;
            try (Connection blocker = dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                execute(blocker, "SELECT id FROM admin_control_lock WHERE id=1 FOR UPDATE");
                pending = async(() -> patch(user.id(), "DISABLED", "等待时失效", token));
                try {
                    awaitWaiters("admin_control_lock", 1);
                    switch (change) {
                        case "session" -> jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP(6) WHERE token_hash=SHA2(?,256)", token);
                        case "role" -> jdbc.update("UPDATE app_user SET role='USER' WHERE id=?", admin.id());
                        default -> jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?", admin.id());
                    }
                } finally { blocker.commit(); }
            }
            assertThat(pending.get(10, TimeUnit.SECONDS).status()).as(change).isEqualTo(change.equals("role") ? 403 : 401);
            assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id=?", String.class, user.id())).isEqualTo("ENABLED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE target_user_id=?", Integer.class, user.id())).isZero();
        }
    }

    private void awaitWaiters(String table, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME=?
                """, Integer.class, table);
            if (waiting >= expected) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Database barrier timed out: expected " + expected + " lock waiters on " + table);
    }
    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static CompletableFuture<Reply> async(RequestCall call) {
        return CompletableFuture.supplyAsync(() -> {
            try { return call.call(); } catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }
    @FunctionalInterface private interface RequestCall { Reply call() throws Exception; }

    private Account register(String displayName) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0,12);
        String username = "d21_" + suffix, password = "Safe_" + suffix;
        Reply registration = request("POST", "/auth/registrations", Map.of("username", username, "password", password, "displayName", displayName), null);
        assertThat(registration.status()).isEqualTo(201);
        long id = registration.body().path("data").path("id").asLong();
        ids.add(id);
        assertThat(registration.body().path("data").path("status").asText()).isEqualTo("ENABLED");
        return new Account(id, username, password, null);
    }

    private String login(Account account, String client) throws Exception {
        Reply reply = request("POST", "/auth/sessions", Map.of("username", account.username(), "password", account.password(), "clientType", client), null);
        assertThat(reply.status()).isEqualTo(201);
        return reply.body().path("data").path("accessToken").asText();
    }

    private Reply patch(long id, String status, String reason, String token) throws Exception {
        return request("PATCH", "/admin/users/" + id + "/status", Map.of("status", status, "reason", reason), token);
    }
    private Reply request(String method, String path, Object body, String token) throws Exception {
        return raw(method, path, body == null ? null : json.writeValueAsString(body), token);
    }
    private Reply raw(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body().isBlank() ? json.nullNode() : json.readTree(response.body()));
    }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record Reply(int status, JsonNode body) { }
    private record Account(long id, String username, String password, String token) {
        Account withToken(String value) { return new Account(id, username, password, value); }
        @Override public String toString() { return "Account[id=" + id + "]"; }
    }
}
