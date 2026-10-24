package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"lms.task.worker.enabled=true", "lms.task.processor.mode=mock",
        "lms.task.worker.poll-ms=50"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class MockWorkerSchedulingIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void scheduledMockWorkerProducesCandidateWithoutManualExecution() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "mock_schedule_" + suffix;
        String password = "Safe_" + suffix;
        long userId = rest.postForEntity(url("/auth/registrations"),
            Map.of("username", username, "password", password, "displayName", username), JsonNode.class)
            .getBody().path("data").path("id").asLong();
        try {
            String token = rest.postForEntity(url("/auth/sessions"), Map.of("username", username,
                "password", password, "clientType", "WEB"), JsonNode.class)
                .getBody().path("data").path("accessToken").asText();
            String materialId = exchange("/materials", HttpMethod.POST,
                Map.of("type", "NOTE", "title", "自动处理", "noteText", "可以引用的真实文字。"), token)
                .path("data").path("id").asText();
            String taskId = exchange("/ai-tasks", HttpMethod.POST,
                Map.of("materialId", materialId, "materialVersion", 1, "requestKey", "scheduled-mock",
                    "goalType", "REVIEW_CARDS", "externalProcessingConsent", true), token)
                .path("data").path("id").asText();
            JsonNode task = null;
            for (int i = 0; i < 50; i++) {
                task = exchange("/ai-tasks/" + taskId, HttpMethod.GET, null, token).path("data");
                if ("WAIT_CONFIRM".equals(task.path("status").asText())) break;
                Thread.sleep(100);
            }
            assertThat(task.path("status").asText()).isEqualTo("WAIT_CONFIRM");
            assertThat(exchange("/ai-tasks/" + taskId + "/result", HttpMethod.GET, null, token)
                .path("data").path("generationMode").asText()).isEqualTo("MOCK");
        } finally {
            jdbc.update("DELETE FROM ai_result WHERE task_id IN (SELECT id FROM ai_task WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM ai_tool_call WHERE task_id IN (SELECT id FROM ai_task WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM ai_task WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM material_chunk WHERE material_id IN (SELECT id FROM material WHERE user_id = ?)", userId);
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
