package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.learningmaterial.system.task.AiTaskDtos.CandidateCard;
import com.learningmaterial.system.task.AiTaskDtos.CandidateResult;
import com.learningmaterial.system.task.AiTaskDtos.SourceRef;
import com.learningmaterial.system.task.MockTaskProcessor;
import com.learningmaterial.system.task.TaskCandidateService;
import com.learningmaterial.system.task.TaskCandidateService.SourceDraft;
import com.learningmaterial.system.task.TaskExecutionException;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "lms.task.processor.mode=mock")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class MockCandidateIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskLeaseService leases;
    @Autowired private TaskCandidateService candidates;
    @Autowired private MockTaskProcessor processor;

    @Test
    void mockCandidateHasVerifiableOwnedSourcesAndIsHiddenAfterCancellation() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Safe_" + suffix;
        long ownerId = register("candidate_" + suffix, password);
        long strangerId = register("other_" + suffix, password);
        try {
            String owner = login("candidate_" + suffix, password);
            String stranger = login("other_" + suffix, password);
            String note = "光合作用将光能转化为化学能。叶绿体是主要场所。";
            String materialId = exchange("/materials", HttpMethod.POST,
                Map.of("type", "NOTE", "title", "生物笔记", "noteText", note), owner)
                .getBody().path("data").path("id").asText();
            String taskId = submit(materialId, "candidate-ready", owner);
            assertThat(exchange("/ai-tasks/" + taskId + "/result", HttpMethod.GET, null, owner)
                .getStatusCode().value()).isEqualTo(404);

            Lease lease = leases.claim();
            assertThat(lease.taskId()).isEqualTo(Long.parseLong(taskId));
            processor.execute(lease);
            JsonNode task = exchange("/ai-tasks/" + taskId, HttpMethod.GET, null, owner)
                .getBody().path("data");
            assertThat(task.path("status").asText()).isEqualTo("WAIT_CONFIRM");
            assertThat(task.path("stepCount").asInt()).isEqualTo(3);
            JsonNode result = exchange("/ai-tasks/" + taskId + "/result", HttpMethod.GET, null, owner)
                .getBody().path("data");
            assertThat(result.path("generationMode").asText()).isEqualTo("MOCK");
            assertThat(result.path("warnings").toString()).contains("模拟生成");
            assertThat(result.path("cards").size()).isPositive();
            JsonNode source = exchange("/ai-tasks/" + taskId + "/sources", HttpMethod.GET, null, owner)
                .getBody().path("data").get(0);
            assertThat(source.path("content").asText()).isEqualTo(note);
            assertThat(source.path("materialVersion").asInt()).isEqualTo(1);
            JsonNode citation = result.path("cards").get(0).path("sources").get(0);
            assertThat(citation.path("chunkId").asText()).isEqualTo(source.path("chunkId").asText());
            assertThat(source.path("content").asText()).contains(citation.path("quoteText").asText());
            assertThat(exchange("/ai-tasks/" + taskId + "/result", HttpMethod.GET, null, stranger)
                .getStatusCode().value()).isEqualTo(404);
            assertThat(exchange("/ai-tasks/" + taskId + "/sources", HttpMethod.GET, null, stranger)
                .getStatusCode().value()).isEqualTo(404);
            jdbc.update("UPDATE material SET access_state = 'RESTRICTED' WHERE id = ?", Long.parseLong(materialId));
            assertThat(exchange("/ai-tasks/" + taskId + "/result", HttpMethod.GET, null, owner)
                .getStatusCode().value()).isEqualTo(403);
            jdbc.update("UPDATE material SET access_state = 'NORMAL' WHERE id = ?", Long.parseLong(materialId));
            exchange("/ai-tasks/" + taskId + "/cancellations", HttpMethod.POST, null, owner);
            assertThat(exchange("/ai-tasks/" + taskId + "/result", HttpMethod.GET, null, owner)
                .getStatusCode().value()).isEqualTo(404);
        } finally { clean(ownerId); clean(strangerId); }
    }

    @Test
    void rejectsBadCitationAndStaleWorkerCannotPublishCandidate() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Safe_" + suffix;
        long userId = register("stale_" + suffix, password);
        try {
            String token = login("stale_" + suffix, password);
            String materialId = exchange("/materials", HttpMethod.POST,
                Map.of("type", "NOTE", "title", "验证", "noteText", "实际原文"), token)
                .getBody().path("data").path("id").asText();
            String taskId = submit(materialId, "stale-candidate", token);
            Lease first = leases.claim();
            var sources = candidates.saveSources(first,
                List.of(new SourceDraft(1, "实际原文", "TEXT", "测试来源")));
            CandidateResult wrong = new CandidateResult(taskId, 1, 1, "MOCK", "模拟摘要", List.of("模拟结果"),
                List.of(new CandidateCard("mock-1", "错误引用", "候选", null, null, List.of(),
                    List.of(new SourceRef(sources.get(0).getId().toString(), "不在原文中", 0, 5)), null)),
                List.of(), null, List.of(), List.of("模拟生成"));
            assertThatThrownBy(() -> candidates.finish(first, wrong))
                .isInstanceOf(TaskExecutionException.class).hasMessage("INVALID_CANDIDATE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE task_id = ?",
                Long.class, first.taskId())).isZero();

            jdbc.update("UPDATE ai_task SET lease_until = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), first.taskId());
            Lease recovered = leases.claim();
            assertThat(recovered).isEqualTo(new Lease(first.taskId(), first.epoch() + 1));
            processor.execute(first);
            assertThat(candidates.finish(first, wrong)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE task_id = ?",
                Long.class, first.taskId())).isZero();
            processor.execute(recovered);
            assertThat(exchange("/ai-tasks/" + taskId, HttpMethod.GET, null, token)
                .getBody().path("data").path("status").asText()).isEqualTo("WAIT_CONFIRM");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE task_id = ?",
                Long.class, first.taskId())).isEqualTo(1);
        } finally { clean(userId); }
    }

    @Test
    void imageCandidateUsesTitleMetadataWithoutClaimingFileWasRead() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Safe_" + suffix;
        long userId = register("image_mock_" + suffix, password);
        try {
            String token = login("image_mock_" + suffix, password);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            jdbc.update("""
                INSERT INTO material (user_id, title, material_type, version, status,
                                      access_state, created_at, updated_at)
                VALUES (?, ?, 'IMAGE', 1, 'ACTIVE', 'NORMAL', ?, ?)
                """, userId, "细胞结构图", now, now);
            String materialId = jdbc.queryForObject(
                "SELECT id FROM material WHERE user_id = ?", String.class, userId);
            String taskId = submit(materialId, "image-metadata", token);

            Lease lease = leases.claim();
            assertThat(lease.taskId()).isEqualTo(Long.parseLong(taskId));
            processor.execute(lease);

            JsonNode result = exchange("/ai-tasks/" + taskId + "/result", HttpMethod.GET, null, token)
                .getBody().path("data");
            JsonNode source = exchange("/ai-tasks/" + taskId + "/sources", HttpMethod.GET, null, token)
                .getBody().path("data").get(0);
            assertThat(result.path("generationMode").asText()).isEqualTo("MOCK");
            assertThat(result.path("processedPages").size()).isZero();
            assertThat(result.path("warnings").toString()).contains("文件正文尚未提取");
            assertThat(source.path("extractionMethod").asText()).isEqualTo("METADATA");
            assertThat(source.path("chunkNo").asInt()).isEqualTo(TaskCandidateService.MOCK_METADATA_CHUNK_NO);
            assertThat(source.path("content").asText()).isEqualTo("细胞结构图");
        } finally { clean(userId); }
    }

    private String submit(String materialId, String requestKey, String token) {
        return exchange("/ai-tasks", HttpMethod.POST, Map.of("materialId", materialId,
            "materialVersion", 1, "requestKey", requestKey, "goalType", "REVIEW_CARDS",
            "externalProcessingConsent", true), token).getBody().path("data").path("id").asText();
    }

    private long register(String username, String password) {
        return rest.postForEntity(url("/auth/registrations"),
            Map.of("username", username, "password", password, "displayName", username), JsonNode.class)
            .getBody().path("data").path("id").asLong();
    }

    private String login(String username, String password) {
        return rest.postForEntity(url("/auth/sessions"), Map.of("username", username,
            "password", password, "clientType", "WEB"), JsonNode.class)
            .getBody().path("data").path("accessToken").asText();
    }

    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void clean(long userId) {
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

    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
