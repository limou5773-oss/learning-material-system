package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"lms.task.worker.enabled=true", "lms.task.worker.poll-ms=50"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class TaskWorkerSchedulingIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void enabledWorkerClaimsAndFailsSafelyWithoutProcessor() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "scheduler_" + suffix;
        String password = "Safe_" + suffix;
        long userId = rest.postForEntity(url("/auth/registrations"), Map.of("username", username,
            "password", password, "displayName", username), JsonNode.class)
            .getBody().path("data").path("id").asLong();
        try {
            String token = rest.postForEntity(url("/auth/sessions"), Map.of("username", username,
                "password", password, "clientType", "WEB"), JsonNode.class)
                .getBody().path("data").path("accessToken").asText();
            String materialId = exchange("/materials", HttpMethod.POST, Map.of("type", "NOTE",
                "title", "调度检查", "noteText", "正文"), token).path("data").path("id").asText();
            String taskId = exchange("/ai-tasks", HttpMethod.POST, Map.of("materialId", materialId,
                "materialVersion", 1, "requestKey", "scheduled", "goalType", "REVIEW_CARDS",
                "externalProcessingConsent", true), token).path("data").path("id").asText();
            JsonNode task = null;
            for (int i = 0; i < 50; i++) {
                task = exchange("/ai-tasks/" + taskId, HttpMethod.GET, null, token).path("data");
                if ("FAILED".equals(task.path("status").asText())) break;
                Thread.sleep(100);
            }
            assertThat(task.path("status").asText()).isEqualTo("FAILED");
            assertThat(task.path("errorCode").asText()).isEqualTo("PROCESSOR_NOT_CONFIGURED");
            assertThat(jdbc.queryForObject("SELECT execution_epoch FROM ai_task WHERE id = ?",
                Integer.class, Long.parseLong(taskId))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_tool_call WHERE task_id = ?",
                Long.class, Long.parseLong(taskId))).isZero();
        } finally {
            jdbc.update("DELETE FROM ai_tool_call WHERE task_id IN (SELECT id FROM ai_task WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM ai_task WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM favorite WHERE user_id = ?", userId);
            jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON mt.material_id = m.id WHERE m.user_id = ?", userId);
            jdbc.update("DELETE FROM material WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM tag WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    private JsonNode exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
    }

    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
