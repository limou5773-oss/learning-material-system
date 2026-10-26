package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learningmaterial.system.task.AiTaskDtos.CandidateCard;
import com.learningmaterial.system.task.AiTaskDtos.CandidateResult;
import com.learningmaterial.system.task.AiTaskDtos.SourceRef;
import com.learningmaterial.system.task.MockTaskProcessor;
import com.learningmaterial.system.task.TaskCandidateService;
import com.learningmaterial.system.task.TaskLeaseService;
import com.learningmaterial.system.task.TaskLeaseService.Lease;
import com.learningmaterial.system.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "lms.task.processor.mode=mock")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class NotificationIntegrationTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskLeaseService leases;
    @Autowired TaskCandidateService candidates;
    @Autowired MockTaskProcessor processor;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired NotificationService notifications;

    @Test
    void taskOwnerForeignKeyRejectsMismatchedNotificationOwnership() {
        try (Fixture f = new Fixture(); Fixture other = new Fixture()) {
            String task = f.submit();
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                notifications.recordTaskState(other.userId, Long.parseLong(task), "FAILED", LocalDateTime.now(ZoneOffset.UTC))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(f.list(1, 20, false).path("total").asInt()).isZero();
            assertThat(other.list(1, 20, false).path("total").asInt()).isZero();
        }
    }

    @Test
    void concurrentEventReplayKeepsOneNotificationAndPreservesFirstReadTime() throws Exception {
        try (Fixture f = new Fixture(); ExecutorService pool = Executors.newFixedThreadPool(4)) {
            String task = f.submit(); f.ready(task);
            String id = f.event(task, "RESULT_READY").path("id").asText(); f.read(id, true);
            LocalDateTime firstRead = jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?", LocalDateTime.class, id);
            JsonNode original = f.event(task, "RESULT_READY");
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> replays = new ArrayList<>();
            for (int i = 0; i < 4; i++) replays.add(pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    notifications.recordTaskState(f.userId, Long.parseLong(task), "WAIT_CONFIRM", LocalDateTime.now(ZoneOffset.UTC)));
                return null;
            }));
            start.countDown(); for (Future<?> replay : replays) replay.get(10, TimeUnit.SECONDS);
            assertThat(f.list(1, 20, false).path("total").asInt()).isEqualTo(1);
            assertThat(f.event(task, "RESULT_READY")).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?", LocalDateTime.class, id)).isEqualTo(firstRead);
            jdbc.update("DELETE FROM ai_result WHERE task_id=?", task); jdbc.update("DELETE FROM ai_tool_call WHERE task_id=?", task);
            jdbc.update("DELETE FROM ai_task WHERE id=?", task);
            assertThat(f.list(1, 20, false).path("total").asInt()).isZero();
        }
    }

    @Test
    void publicationAndFailureDoNotDeadlockSameUserSubmitOrRetry() throws Exception {
        for (String transition : List.of("WAIT_CONFIRM", "FAILED")) {
            for (String operation : List.of("submit", "retry")) {
                    try (Fixture f = new Fixture(); ExecutorService pool = Executors.newFixedThreadPool(2)) {
                        String parent = f.submit(); assertThat(leases.fail(f.claim(parent), "PARENT_FAILED")).isTrue();
                        String task = f.submit(); Lease lease = f.claim(task);
                        CandidateResult candidate = f.candidate(lease, "MOCK");
                        String gate = "d20_gate_" + f.userId, trigger = "d20_gate_" + f.userId;
                        CompletableFuture<Boolean> publishing = null;
                        CompletableFuture<ResponseEntity<JsonNode>> submitting = null;
                        try (Connection blocker = dataSource.getConnection()) {
                            namedLock(blocker, "GET_LOCK", gate);
                            jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON notification FOR EACH ROW BEGIN IF NEW.task_id=" + task + " THEN SET @d20_gate_result=GET_LOCK('" + gate + "',10); IF @d20_gate_result<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='publication barrier timeout'; END IF; DO RELEASE_LOCK('" + gate + "'); END IF; END");
                            try {
                                publishing = CompletableFuture.supplyAsync(() -> transition.equals("WAIT_CONFIRM") ? candidates.finish(lease, candidate) : leases.fail(lease, "CONTROLLED_FAILURE"), pool);
                                awaitSql("SELECT COUNT(*) FROM performance_schema.metadata_locks WHERE OBJECT_TYPE='USER LEVEL LOCK' AND OBJECT_NAME=? AND LOCK_STATUS='PENDING'", gate);
                                submitting = CompletableFuture.supplyAsync(() -> operation.equals("submit") ? f.submitResponse() : exchange("/ai-tasks/" + parent + "/retries", HttpMethod.POST,
                                    Map.of("materialVersion", 1, "requestKey", UUID.randomUUID().toString(), "externalProcessingConsent", true), f.token), pool);
                                awaitSql("SELECT COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_SCHEMA=DATABASE() AND OBJECT_NAME='material' AND LOCK_STATUS='WAITING'");
                                System.out.println("D20 controlled " + transition + "/" + operation + " material locks=" + jdbc.queryForList("SELECT r.LOCK_MODE AS waiting_mode,b.LOCK_MODE AS blocking_mode FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID WHERE r.OBJECT_NAME='material'"));
                                namedLock(blocker, "RELEASE_LOCK", gate);
                                assertThat(publishing.get(5, TimeUnit.SECONDS)).isTrue();
                                assertThat(submitting.get(15, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                                assertThat(f.event(task, transition.equals("WAIT_CONFIRM") ? "RESULT_READY" : "TASK_FAILED")).isNotNull();
                            } finally {
                                namedLock(blocker, "RELEASE_LOCK", gate);
                                if (publishing != null) { try { publishing.get(15, TimeUnit.SECONDS); } catch (ExecutionException ex) { System.out.println("D20 controlled publication failure=" + ex.getCause().getClass().getSimpleName()); } }
                                if (submitting != null) submitting.get(15, TimeUnit.SECONDS);
                                jdbc.execute("DROP TRIGGER " + trigger);
                            }
                        }
                    }
                }
        }
    }

    private void namedLock(Connection connection, String function, String name) throws Exception {
        try (var statement = connection.prepareStatement("SELECT " + function + "(?" + (function.equals("GET_LOCK") ? ",5" : "") + ")")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                if (function.equals("GET_LOCK")) assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }
    private void awaitSql(String sql, Object... args) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject(sql, Long.class, args) > 0) return;
            Thread.sleep(10);
        }
        System.out.println("D20 barrier data waits=" + jdbc.queryForList("SELECT r.OBJECT_NAME,r.INDEX_NAME,r.LOCK_TYPE,r.LOCK_MODE,r.LOCK_DATA FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID"));
        System.out.println("D20 barrier metadata waits=" + jdbc.queryForList("SELECT OBJECT_TYPE,OBJECT_NAME,LOCK_TYPE,LOCK_DURATION,LOCK_STATUS FROM performance_schema.metadata_locks WHERE LOCK_STATUS='PENDING'"));
        throw new AssertionError("Timed out waiting for controlled database lock: " + sql);
    }

    @Test
    void ownedPaginationUnreadStateAndCrossClientConsistency() {
        try (Fixture f = new Fixture(); Fixture other = new Fixture()) {
            assertThat(exchange("/notifications", HttpMethod.GET, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(f.list(1, 20, false).path("total").asInt()).isZero();
            String first = f.submit(); f.ready(first);
            String second = f.submit(); f.ready(second);
            // Equal timestamps exercise the stable BIGINT tie breaker.
            jdbc.update("UPDATE notification SET created_at='2026-10-01 00:00:00' WHERE user_id=?", f.userId);
            JsonNode page = f.list(1, 1, false);
            assertThat(page.path("total").asInt()).isEqualTo(2);
            assertThat(page.path("page").asInt()).isEqualTo(1);
            assertThat(page.path("pageSize").asInt()).isEqualTo(1);
            JsonNode item = page.path("items").get(0);
            assertThat(item.path("id").isTextual()).isTrue();
            assertThat(item.path("id").asText()).matches("[1-9][0-9]*");
            assertThat(item.path("target").path("taskId").asText()).isEqualTo(second);
            assertThat(item.path("target").path("type").asText()).isEqualTo("AI_TASK");
            assertThat(item.path("type").asText()).isEqualTo("RESULT_READY");
            assertThat(item.path("channelStatus").asText()).isEqualTo("NOT_REQUESTED");
            assertThat(item.path("createdAt").asText()).endsWith("Z");
            assertThat(item.path("content").asText()).isNotBlank().doesNotContain("原文秘密", "token", "openid");
            assertThat(f.list(2, 1, false).path("items").get(0).path("target").path("taskId").asText()).isEqualTo(first);
            assertThat(f.list(3, 1, false).path("items")).isEmpty();
            assertThat(other.list(1, 20, false).path("total").asInt()).isZero();
            String id = item.path("id").asText();
            assertThat(exchange("/notifications/" + id + "/read-state", HttpMethod.PUT, Map.of("isRead", true), other.token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(data(exchange("/notifications/" + id + "/read-state", HttpMethod.PUT, Map.of("isRead", true), f.mini)).path("isRead").asBoolean()).isTrue();
            LocalDateTime firstRead = jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?", LocalDateTime.class, id);
            assertThat(firstRead).isNotNull();
            assertThat(f.read(id, true).path("createdAt")).isEqualTo(item.path("createdAt"));
            assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?", LocalDateTime.class, id)).isEqualTo(firstRead);
            assertThat(f.list(1, 20, true).path("total").asInt()).isEqualTo(1);
            assertThat(f.read(id, false).path("isRead").asBoolean()).isFalse();
            assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?", LocalDateTime.class, id)).isNull();
            assertThat(f.list(1, 20, true).path("total").asInt()).isEqualTo(2);
            assertThat(data(exchange("/notifications", HttpMethod.GET, null, f.mini))).isEqualTo(f.list(1, 20, false));
            assertThat(jdbc.queryForObject("SELECT dedupe_key FROM notification WHERE id=?", String.class, id)).isEqualTo("task:" + second + ":WAIT_CONFIRM");
        }
    }

    @Test
    void rejectsMalformedReadStateAndInvalidPaginationWithoutMutations() {
        try (Fixture f = new Fixture()) {
            String task = f.submit(); f.ready(task);
            String id = f.list(1, 20, false).path("items").get(0).path("id").asText();
            for (String invalid : List.of("{\"isRead\":\"true\"}", "{\"isRead\":1}", "{\"isRead\":[]}", "{\"isRead\":{}}", "[]", "{\"isRead\":true,\"content\":\"forged\"}", "{"))
                assertThat(exchange("/notifications/" + id + "/read-state", HttpMethod.PUT, invalid, f.token).getStatusCode()).as(invalid).isEqualTo(HttpStatus.BAD_REQUEST);
            for (String missing : List.of("{}", "{\"isRead\":null}"))
                assertThat(exchange("/notifications/" + id + "/read-state", HttpMethod.PUT, missing, f.token).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            for (String invalid : List.of("0", "-1", "garbage", "9223372036854775808", "999999999999999999999"))
                assertThat(exchange("/notifications/" + invalid + "/read-state", HttpMethod.PUT, Map.of("isRead", true), f.token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            for (String query : List.of("page=0", "pageSize=0", "pageSize=101", "page=-1"))
                assertThat(exchange("/notifications?" + query, HttpMethod.GET, null, f.token).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            for (String query : List.of("unreadOnly=", "unreadOnly=1", "unreadOnly=TRUE", "unreadOnly=wrong", "page=x"))
                assertThat(exchange("/notifications?" + query, HttpMethod.GET, null, f.token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(f.list(1, 20, true).path("total").asInt()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" {\"content\":\"extra\"}", " []", " trailing-invalid-json"})
    void trailingReadStateContentIsRejectedBeforeAnyWrite(String trailing) {
        try (Fixture f = new Fixture()) {
            String task = f.submit(); f.ready(task);
            String id = f.event(task, "RESULT_READY").path("id").asText();
            var unreadResponse = exchange("/notifications/" + id + "/read-state", HttpMethod.PUT,
                "{\"isRead\":true}" + trailing, f.token);
            assertSoftly(soft -> {
                soft.assertThat(unreadResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                soft.assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?", LocalDateTime.class, id)).isNull();
            });
            f.read(id, true);
            LocalDateTime firstRead = jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?", LocalDateTime.class, id);
            var readResponse = exchange("/notifications/" + id + "/read-state", HttpMethod.PUT,
                "{\"isRead\":false}" + trailing, f.token);
            assertSoftly(soft -> {
                soft.assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                soft.assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?", LocalDateTime.class, id)).isEqualTo(firstRead);
            });
        }
    }

    @Test
    void everyFailedTransitionCreatesOnlyItsOwnEventAndStaleSourceCommitsExplicitly() {
        try (Fixture f = new Fixture()) {
            String failed = f.submit(); Lease lease = f.claim(failed);
            assertThat(leases.fail(lease, "PROVIDER_SECRET_FAILURE")).isTrue();
            assertThat(leases.fail(lease, "PROVIDER_SECRET_FAILURE")).isFalse();
            assertThat(f.event(failed, "TASK_FAILED").path("content").asText()).doesNotContain("PROVIDER_SECRET_FAILURE");
            String timeout = f.submit(); Lease timed = f.claim(timeout);
            jdbc.update("UPDATE ai_task SET started_at=?,lease_until=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(400), LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), timeout);
            assertThat(leases.claim()).isNull();
            assertThat(f.event(timeout, "TASK_FAILED").path("type").asText()).isEqualTo("TASK_FAILED");
            assertThat(leases.fail(timed, "LATE")).isFalse();
            String stale = f.submit(); f.ready(stale);
            ObjectNode input = f.confirmation(stale);
            jdbc.update("UPDATE material SET version=2 WHERE id=?", f.materialId);
            var response = exchange("/ai-tasks/" + stale + "/confirmation", HttpMethod.PUT, input, f.token);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().path("error").path("code").asText()).isEqualTo("SOURCE_VERSION_CHANGED");
            assertThat(f.status(stale)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT status FROM ai_result WHERE task_id=?", String.class, stale)).isEqualTo("INVALIDATED");
            assertThat(f.event(stale, "TASK_FAILED").path("type").asText()).isEqualTo("TASK_FAILED");
            assertThat(f.list(1, 20, false).path("total").asInt()).isEqualTo(4);
        }
    }

    @Test
    void oldEpochCancelledAndDuplicatePublicationDoNotInventNotifications() {
        try (Fixture f = new Fixture()) {
            String task = f.submit(); Lease old = f.claim(task);
            CandidateResult result = f.candidate(old, "MOCK");
            jdbc.update("UPDATE ai_task SET lease_until=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), task);
            Lease fresh = f.claim(task);
            assertThat(candidates.finish(old, result)).isFalse();
            assertThat(leases.fail(old, "OLD_EPOCH")).isFalse();
            assertThat(f.list(1, 20, false).path("total").asInt()).isZero();
            assertThat(candidates.finish(fresh, result)).isTrue();
            String id = f.event(task, "RESULT_READY").path("id").asText(); f.read(id, true);
            assertThat(candidates.finish(fresh, result)).isFalse();
            assertThat(leases.fail(fresh, "ALREADY_FINISHED")).isFalse();
            assertThat(f.event(task, "RESULT_READY").path("isRead").asBoolean()).isTrue();
            String cancelled = f.submit(); Lease cancelledLease = f.claim(cancelled);
            CandidateResult unused = f.candidate(cancelledLease, "MOCK");
            assertThat(exchange("/ai-tasks/" + cancelled + "/cancellations", HttpMethod.POST, null, f.token).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(candidates.finish(cancelledLease, unused)).isFalse();
            assertThat(leases.fail(cancelledLease, "CANCELLED")).isFalse();
            assertThat(leases.claim()).isNull();
            assertThat(f.list(1, 20, false).path("total").asInt()).isEqualTo(1);
            // MODEL and agent publication use the same checked finish entry point.
            String model = f.submit(); Lease modelLease = f.claim(model);
            assertThat(candidates.finish(modelLease, f.candidate(modelLease, "MODEL"))).isTrue();
            assertThat(f.event(model, "RESULT_READY").path("type").asText()).isEqualTo("RESULT_READY");
        }
    }

    @Test
    void notificationInsertFailureRollsBackCandidateAndEveryFailedTransition() {
        try (Fixture f = new Fixture()) {
            String task = f.submit(); Lease lease = f.claim(task); CandidateResult candidate = f.candidate(lease, "MOCK");
            String trigger = "d20_failure_" + f.userId;
            jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON notification FOR EACH ROW BEGIN IF NEW.user_id=" + f.userId + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='induced notification write failure'; END IF; END");
            try {
                assertThatThrownBy(() -> candidates.finish(lease, candidate)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(f.status(task)).isEqualTo("RUNNING");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE task_id=?", Long.class, task)).isZero();
                assertThatThrownBy(() -> leases.fail(lease, "TEST_FAILURE")).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(f.status(task)).isEqualTo("RUNNING");
                jdbc.update("UPDATE ai_task SET started_at=?,lease_until=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(400), LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), task);
                assertThatThrownBy(() -> leases.claim()).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(f.status(task)).isEqualTo("RUNNING");
                assertThat(f.list(1, 20, false).path("total").asInt()).isZero();
            } finally { jdbc.execute("DROP TRIGGER " + trigger); }
            jdbc.update("UPDATE ai_task SET started_at=?,lease_until=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30), task);
            assertThat(candidates.finish(lease, candidate)).isTrue();
            ObjectNode input = f.confirmation(task);
            jdbc.update("UPDATE material SET version=2 WHERE id=?", f.materialId);
            jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON notification FOR EACH ROW BEGIN IF NEW.user_id=" + f.userId + " AND NEW.type='TASK_FAILED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='induced notification write failure'; END IF; END");
            try {
                assertThat(exchange("/ai-tasks/" + task + "/confirmation", HttpMethod.PUT, input, f.token).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(f.status(task)).isEqualTo("WAIT_CONFIRM");
                assertThat(jdbc.queryForObject("SELECT status FROM ai_result WHERE task_id=?", String.class, task)).isEqualTo("CANDIDATE");
                assertThat(f.list(1, 20, false).path("total").asInt()).isEqualTo(1);
            } finally { jdbc.execute("DROP TRIGGER " + trigger); }
            assertThat(exchange("/ai-tasks/" + task + "/confirmation", HttpMethod.PUT, input, f.token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(f.event(task, "TASK_FAILED")).isNotNull();
        }
    }

    private class Fixture implements AutoCloseable {
        final long userId;
        final String token, mini, materialId;
        Fixture() {
            String name = "d20_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), password = "Safe_" + name;
            JsonNode user = data(rest.postForEntity(url("/auth/registrations"), Map.of("username", name, "password", password, "displayName", name), JsonNode.class));
            userId = user.path("id").asLong(); assertThat(userId).isPositive();
            assertThat(user.path("status").asText()).isEqualTo("ENABLED");
            token = login(name, password, "WEB"); mini = login(name, password, "MINIPROGRAM");
            materialId = data(exchange("/materials", HttpMethod.POST, Map.of("type", "NOTE", "title", "通知验证", "noteText", "原文秘密，不得进入通知正文。"), token)).path("id").asText();
        }
        ResponseEntity<JsonNode> submitResponse() { return exchange("/ai-tasks", HttpMethod.POST, Map.of("materialId", materialId, "materialVersion", 1, "requestKey", UUID.randomUUID().toString(), "goalType", "REVIEW_CARDS", "externalProcessingConsent", true), token); }
        String submit() { return data(submitResponse()).path("id").asText(); }
        Lease claim(String task) { Lease lease = leases.claim(); assertThat(lease).isNotNull(); assertThat(lease.taskId()).isEqualTo(Long.parseLong(task)); return lease; }
        void ready(String task) { processor.execute(claim(task)); assertThat(status(task)).isEqualTo("WAIT_CONFIRM"); }
        CandidateResult candidate(Lease lease, String mode) {
            var source = candidates.saveSources(lease, List.of(new TaskCandidateService.SourceDraft(1, "原文秘密", "TEXT", "验证"))).get(0);
            return new CandidateResult(Long.toString(lease.taskId()), 1, 1, mode, "摘要", List.of(),
                List.of(new CandidateCard("mock-1", "标题", "正文", null, null, List.of(), List.of(new SourceRef(source.getId().toString(), "原文秘密", 0, 4)), null)),
                List.of(), null, List.of(), List.of("验证来源"), "MODEL".equals(mode) ? new com.learningmaterial.system.task.AiTaskDtos.GenerationInfo("test", "test", "v1") : null);
        }
        ObjectNode confirmation(String task) {
            JsonNode candidate = data(exchange("/ai-tasks/" + task + "/result", HttpMethod.GET, null, token));
            ObjectNode input = JsonNodeFactory.instance.objectNode();
            input.put("resultVersion", 1).put("sourceVersion", 1).put("summary", "核对摘要").put("tagMode", "MERGE");
            input.putArray("tags"); input.putArray("cards").add(candidate.path("cards").get(0).deepCopy()); input.putArray("relations"); return input;
        }
        String status(String task) { return data(exchange("/ai-tasks/" + task, HttpMethod.GET, null, token)).path("status").asText(); }
        JsonNode list(int page, int size, boolean unread) { return data(exchange("/notifications?page=" + page + "&pageSize=" + size + "&unreadOnly=" + unread, HttpMethod.GET, null, token)); }
        JsonNode read(String id, boolean read) { return data(exchange("/notifications/" + id + "/read-state", HttpMethod.PUT, Map.of("isRead", read), token)); }
        JsonNode event(String task, String type) {
            List<JsonNode> found = new ArrayList<>(); list(1, 100, false).path("items").forEach(n -> { if (n.path("target").path("taskId").asText().equals(task) && n.path("type").asText().equals(type)) found.add(n); });
            assertThat(found).hasSize(1); return found.get(0);
        }
        public void close() {
            jdbc.update("DELETE FROM ai_result WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", userId);
            jdbc.update("DELETE FROM ai_tool_call WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", userId);
            jdbc.update("UPDATE ai_task SET parent_task_id=NULL WHERE user_id=?", userId);
            jdbc.update("DELETE FROM ai_task WHERE user_id=?", userId);
            jdbc.update("DELETE FROM material_chunk WHERE material_id IN (SELECT id FROM material WHERE user_id=?)", userId);
            jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON m.id=mt.material_id WHERE m.user_id=?", userId);
            jdbc.update("DELETE FROM material WHERE user_id=?", userId); jdbc.update("DELETE FROM tag WHERE user_id=?", userId);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", userId); jdbc.update("DELETE FROM app_user WHERE id=?", userId);
        }
    }
    private String login(String name, String password, String type) { return data(rest.postForEntity(url("/auth/sessions"), Map.of("username", name, "password", password, "clientType", type), JsonNode.class)).path("accessToken").asText(); }
    private JsonNode data(ResponseEntity<JsonNode> response) { assertThat(response.getStatusCode()).as("response: %s", response.getBody()).isIn(HttpStatus.OK, HttpStatus.CREATED, HttpStatus.ACCEPTED); return response.getBody().path("data"); }
    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders(); if (token != null) headers.setBearerAuth(token); if (body instanceof String) headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(java.net.URI.create(url(path)), method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
