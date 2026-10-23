package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.learningmaterial.system.task.TaskLeaseService;
import com.learningmaterial.system.task.TaskLeaseService.Lease;
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
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class AiTaskWorkerIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskLeaseService leases;

    @Test
    void leaseRecoveryRejectsLateWritesAndCancellationIsIdempotent() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "worker_" + suffix;
        String password = "Safe_" + suffix;
        long userId = register(username, password);
        long strangerId = register("stranger_" + suffix, password);
        try {
            String token = login(username, password);
            String stranger = login("stranger_" + suffix, password);
            String materialId = exchange("/materials", HttpMethod.POST,
                Map.of("type", "NOTE", "title", "受控执行", "noteText", "测试内容"), token)
                .getBody().path("data").path("id").asText();
            String taskId = exchange("/ai-tasks", HttpMethod.POST,
                Map.of("materialId", materialId, "materialVersion", 1, "requestKey", "worker-first",
                    "goalType", "REVIEW_CARDS", "externalProcessingConsent", true), token)
                .getBody().path("data").path("id").asText();
            long id = Long.parseLong(taskId);
            assertThat(exchange("/ai-tasks/" + taskId + "/steps", HttpMethod.GET, null, stranger).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exchange("/ai-tasks/" + taskId + "/cancellations", HttpMethod.POST, null, stranger)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            Lease first = leases.claim();
            assertThat(first).isEqualTo(new Lease(id, 1));
            assertThat(leases.heartbeat(first)).isTrue();
            assertThatThrownBy(() -> leases.startStep(first, "delete_all_data", "LOCAL"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(leases.saveProgress(first, "EXTRACTING", "{\"processedPages\":0}")).isTrue();
            Long firstCall = leases.startStep(first, "extract_pdf_text", "LOCAL");
            assertThat(firstCall).isNotNull();
            assertThat(exchange("/ai-tasks/" + taskId + "/steps", HttpMethod.GET, null, token)
                .getBody().path("data").path("total").asLong()).isEqualTo(1);

            jdbc.update("UPDATE ai_task SET lease_until = ? WHERE id = ?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), id);
            Lease second = leases.claim();
            assertThat(second).isEqualTo(new Lease(id, 2));
            assertThat(leases.heartbeat(first)).isFalse();
            assertThat(leases.saveProgress(first, "GENERATING", "{\"candidate\":\"late\"}")).isFalse();
            assertThat(leases.startStep(first, "generate_summary_cards", "MODEL")).isNull();
            assertThat(leases.completeStep(first, firstCall, true, "迟到的模型结果", null)).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_tool_call WHERE id = ?", String.class, firstCall))
                .isEqualTo("UNKNOWN");
            Long secondCall = leases.startStep(second, "recognize_image", "OCR");
            assertThat(secondCall).isNotNull();
            assertThat(leases.completeStep(second, firstCall, true, "错误轮次", null)).isFalse();
            assertThat(exchange("/ai-tasks/" + taskId, HttpMethod.GET, null, token)
                .getBody().path("data").path("stepCount").asInt()).isEqualTo(2);

            JsonNode cancelled = exchange("/ai-tasks/" + taskId + "/cancellations", HttpMethod.POST, null, token)
                .getBody().path("data");
            assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(cancelled.path("canRetry").asBoolean()).isTrue();
            assertThat(exchange("/ai-tasks/" + taskId + "/cancellations", HttpMethod.POST, null, token)
                .getBody().path("data").path("status").asText()).isEqualTo("CANCELLED");
            assertThat(leases.heartbeat(second)).isFalse();
            assertThat(leases.saveProgress(second, "GENERATING", "{\"candidate\":\"cancelled\"}")).isFalse();
            assertThat(jdbc.queryForObject("SELECT CAST(context_json AS CHAR) FROM ai_task WHERE id = ?",
                String.class, id)).contains("processedPages").doesNotContain("candidate");
            assertThat(leases.completeStep(second, secondCall, true, "取消后的结果", null)).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_tool_call WHERE id = ?", String.class, secondCall))
                .isEqualTo("UNKNOWN");
            assertThat(exchange("/ai-tasks/" + taskId + "/retries", HttpMethod.POST,
                Map.of("requestKey", "retry-first", "materialVersion", 1,
                    "externalProcessingConsent", false), token).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            Map<String, Object> retry = Map.of("requestKey", "retry-first", "materialVersion", 1,
                "externalProcessingConsent", true);
            JsonNode retried = exchange("/ai-tasks/" + taskId + "/retries", HttpMethod.POST, retry, token)
                .getBody().path("data");
            assertThat(retried.path("parentTaskId").asText()).isEqualTo(taskId);
            assertThat(retried.path("status").asText()).isEqualTo("QUEUED");
            assertThat(exchange("/ai-tasks/" + taskId + "/retries", HttpMethod.POST, retry, token)
                .getBody().path("data").path("id").asText()).isEqualTo(retried.path("id").asText());
            assertThat(exchange("/ai-tasks/" + taskId + "/retries", HttpMethod.POST, retry, stranger)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            exchange("/ai-tasks/" + retried.path("id").asText() + "/cancellations", HttpMethod.POST, null, token);
        } finally {
            clean(userId);
            clean(strangerId);
        }
    }

    @Test
    void expiredExecutionFailsAfterTotalDeadline() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "timeout_" + suffix;
        String password = "Safe_" + suffix;
        long userId = register(username, password);
        try {
            String token = login(username, password);
            String materialId = exchange("/materials", HttpMethod.POST,
                Map.of("type", "NOTE", "title", "超时任务", "noteText", "正文"), token)
                .getBody().path("data").path("id").asText();
            String taskId = exchange("/ai-tasks", HttpMethod.POST,
                Map.of("materialId", materialId, "materialVersion", 1, "requestKey", "timeout-first",
                    "goalType", "REVIEW_CARDS", "externalProcessingConsent", true), token)
                .getBody().path("data").path("id").asText();
            Lease lease = leases.claim();
            assertThat(lease.taskId()).isEqualTo(Long.parseLong(taskId));
            jdbc.update("UPDATE ai_task SET started_at = ?, lease_until = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(301),
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), lease.taskId());
            assertThat(leases.claim()).isNull();
            assertThat(exchange("/ai-tasks/" + taskId, HttpMethod.GET, null, token)
                .getBody().path("data").path("errorCode").asText()).isEqualTo("TASK_TIMEOUT");
            assertThat(leases.heartbeat(lease)).isFalse();
        } finally { clean(userId); }
    }

    private long register(String username, String password) {
        ResponseEntity<JsonNode> result = rest.postForEntity(url("/auth/registrations"),
            Map.of("username", username, "password", password, "displayName", username), JsonNode.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return result.getBody().path("data").path("id").asLong();
    }

    private String login(String username, String password) {
        return rest.postForEntity(url("/auth/sessions"), Map.of("username", username,
            "password", password, "clientType", "WEB"), JsonNode.class)
            .getBody().path("data").path("accessToken").asText();
    }

    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void clean(long userId) {
        jdbc.update("DELETE FROM ai_tool_call WHERE task_id IN (SELECT id FROM ai_task WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM ai_task WHERE user_id = ? AND parent_task_id IS NOT NULL", userId);
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
