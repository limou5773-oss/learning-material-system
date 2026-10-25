package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class AiTaskFlowIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @Test
    void submissionIsIdempotentPrivateQuotaBoundAndRecoverableById() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Safe_" + suffix;
        long ownerId = register("task_" + suffix, password);
        long otherId = register("taskother_" + suffix, password);
        try {
            String owner = login("task_" + suffix, password, "WEB");
            String mini = login("task_" + suffix, password, "MINIPROGRAM");
            String other = login("taskother_" + suffix, password, "WEB");
            String noteId = note(owner, "线性代数", "矩阵和特征值");
            String foreignId = note(other, "他人笔记", "保密");
            Map<String, Object> firstInput = input(noteId, 1, "first-key", "REVIEW_CARDS");
            assertThat(post(firstInput, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(post(input(foreignId, 1, "foreign-key", "REVIEW_CARDS"), owner).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(post(Map.of("materialId", noteId, "materialVersion", 1, "requestKey", "no-consent",
                "goalType", "REVIEW_CARDS", "externalProcessingConsent", false), owner).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(post(Map.of("materialId", noteId, "materialVersion", 1, "requestKey", "wrong-range",
                "goalType", "REVIEW_CARDS", "externalProcessingConsent", true,
                "pageRange", Map.of("from", 1, "to", 2)), owner).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(post(input(noteId, 2, "stale-key", "REVIEW_CARDS"), owner).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

            ResponseEntity<JsonNode> created = post(firstInput, owner);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            JsonNode task = created.getBody().path("data");
            String taskId = task.path("id").asText();
            assertThat(task.path("status").asText()).isEqualTo("QUEUED");
            assertThat(task.path("stepCount").asInt()).isZero();
            assertThat(task.path("canCancel").asBoolean()).isTrue();
            assertThat(get("/ai-tasks/" + taskId, mini).getBody().path("data").path("id").asText())
                .isEqualTo(taskId);
            assertThat(get("/ai-tasks/" + taskId, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            String hash = jdbc.queryForObject("SELECT request_hash FROM ai_task WHERE id = ?", String.class, Long.parseLong(taskId));
            assertThat(hash).matches("[0-9a-f]{64}");
            String config = jdbc.queryForObject("SELECT CAST(config_json AS CHAR) FROM ai_task WHERE id = ?",
                String.class, Long.parseLong(taskId));
            JsonNode saved = json.readTree(config);
            assertThat(saved.path("inputSnapshot").path("noteText").asText()).isEqualTo("矩阵和特征值");
            assertThat(saved.path("externalProcessingConsentAt").asText()).isNotBlank();
            assertThat(saved.path("modelConfig").path("provider").asText()).isEqualTo("disabled");
            assertThat(saved.path("modelConfig").path("model").asText()).isEqualTo("deepseek-flash");
            assertThat(saved.path("modelConfig").path("status").asText()).isEqualTo("NOT_CONFIGURED");

            assertThat(post(firstInput, mini).getBody().path("data").path("id").asText()).isEqualTo(taskId);
            assertThat(post(Map.of("materialId", noteId, "materialVersion", 1, "requestKey", "first-key",
                "goalType", "LINK_NOTES", "externalProcessingConsent", true), owner).getBody()
                .path("error").path("code").asText()).isEqualTo("REQUEST_KEY_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE user_id = ?", Long.class, ownerId))
                .isEqualTo(1L);

            assertThat(exchange("/materials/" + noteId, HttpMethod.PATCH,
                Map.of("version", 1, "noteText", "修改后正文"), owner).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(post(firstInput, owner).getBody().path("data").path("id").asText()).isEqualTo(taskId);
            String secondId = post(input(noteId, 2, "second-key", "EXTRACT_KEY_POINTS"), owner)
                .getBody().path("data").path("id").asText();
            assertThat(post(input(noteId, 2, "third-key", "LINK_NOTES"), owner).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(post(input(noteId, 2, "second-key", "EXTRACT_KEY_POINTS"), owner)
                .getBody().path("data").path("id").asText()).isEqualTo(secondId);
            assertThat(get("/ai-tasks?status=QUEUED&page=1&pageSize=1", owner)
                .getBody().path("data").path("total").asLong()).isEqualTo(2);
            assertThat(get("/ai-tasks?status=QUEUED&page=3&pageSize=1", owner)
                .getBody().path("data").path("items").size()).isZero();
            assertThat(get("/ai-tasks?page=0", owner).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(get("/ai-tasks?pageSize=101", owner).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(get("/ai-tasks?status=UNKNOWN", owner).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(get("/ai-tasks", other).getBody().path("data").path("total").asLong()).isZero();

            assertThat(exchange("/materials/" + noteId + "?version=2", HttpMethod.DELETE, null, owner)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(get("/ai-tasks/" + taskId, owner).getBody().path("data").path("status").asText())
                .isEqualTo("CANCELLED");
            assertThat(get("/ai-tasks/" + taskId, owner).getBody().path("data").path("canRetry").asBoolean())
                .isFalse();
            assertThat(get("/ai-tasks/" + secondId, owner).getBody().path("data").path("status").asText())
                .isEqualTo("CANCELLED");
            assertThat(post(input(noteId, 2, "deleted-key", "REVIEW_CARDS"), owner).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

            String pdfId = pdfFixture(ownerId);
            assertThat(post(input(pdfId, 1, "pdf-no-range", "EXTRACT_KEY_POINTS"), owner).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(post(pdfInput(pdfId, "pdf-too-long", 1, 21), owner).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(post(pdfInput(pdfId, "pdf-outside", 21, 31), owner).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            String pdfTaskId = post(pdfInput(pdfId, "pdf-ok", 1, 20), owner)
                .getBody().path("data").path("id").asText();
            assertThat(get("/ai-tasks/" + pdfTaskId, owner).getBody().path("data").path("status").asText())
                .isEqualTo("QUEUED");
            assertThat(exchange("/materials/" + pdfId + "?version=1", HttpMethod.DELETE, null, owner)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            String raceMaterial = note(owner, "并发资料", "并发正文");
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<HttpStatus>> attempts = List.of("race-a", "race-b", "race-c").stream()
                .map(key -> CompletableFuture.supplyAsync(() -> {
                    try { start.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ex) { throw new RuntimeException(ex); }
                    return (HttpStatus) post(input(raceMaterial, 1, key, "REVIEW_CARDS"), owner).getStatusCode();
                })).toList();
            start.countDown();
            List<HttpStatus> statuses = attempts.stream().map(future -> {
                try { return future.get(15, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }).toList();
            assertThat(statuses).containsExactlyInAnyOrder(
                HttpStatus.ACCEPTED, HttpStatus.ACCEPTED, HttpStatus.TOO_MANY_REQUESTS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE user_id = ? AND status = 'QUEUED'",
                Long.class, ownerId)).isEqualTo(2L);
        } finally {
            clean(ownerId);
            clean(otherId);
        }
    }

    private String pdfFixture(long userId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
            INSERT INTO material (user_id, title, material_type, page_count, version, status, access_state, created_at, updated_at)
            VALUES (?, '页段测试资料', 'PDF', 30, 1, 'ACTIVE', 'NORMAL', ?, ?)
            """, userId, now, now);
        return jdbc.queryForObject("SELECT MAX(id) FROM material WHERE user_id = ?", Long.class, userId).toString();
    }

    private String note(String token, String title, String body) {
        return exchange("/materials", HttpMethod.POST,
            Map.of("type", "NOTE", "title", title, "noteText", body), token)
            .getBody().path("data").path("id").asText();
    }

    private Map<String, Object> input(String materialId, int version, String key, String goal) {
        return Map.of("materialId", materialId, "materialVersion", version, "requestKey", key,
            "goalType", goal, "externalProcessingConsent", true);
    }

    private Map<String, Object> pdfInput(String materialId, String key, int from, int to) {
        return Map.of("materialId", materialId, "materialVersion", 1, "requestKey", key,
            "goalType", "EXTRACT_KEY_POINTS", "externalProcessingConsent", true,
            "pageRange", Map.of("from", from, "to", to));
    }

    private ResponseEntity<JsonNode> post(Object body, String token) {
        return exchange("/ai-tasks", HttpMethod.POST, body, token);
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return exchange(path, HttpMethod.GET, null, token);
    }

    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private long register(String username, String password) {
        ResponseEntity<JsonNode> response = rest.postForEntity(url("/auth/registrations"),
            Map.of("username", username, "password", password, "displayName", username), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("data").path("id").asLong();
    }

    private String login(String username, String password, String clientType) {
        return rest.postForEntity(url("/auth/sessions"), Map.of("username", username,
            "password", password, "clientType", clientType), JsonNode.class)
            .getBody().path("data").path("accessToken").asText();
    }

    private void clean(long userId) {
        jdbc.update("DELETE FROM ai_tool_call WHERE task_id IN (SELECT id FROM ai_task WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM ai_task WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM favorite WHERE user_id = ?", userId);
        jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON mt.material_id = m.id WHERE m.user_id = ?", userId);
        jdbc.update("DELETE FROM material WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tag WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
