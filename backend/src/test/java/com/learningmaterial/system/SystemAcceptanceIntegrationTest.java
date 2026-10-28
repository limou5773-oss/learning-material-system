package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learningmaterial.system.service.TaskLeaseService;
import com.learningmaterial.system.task.MockTaskProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "lms.task.processor.mode=mock", "lms.task.worker.enabled=false",
    "lms.model.provider=disabled", "lms.ocr.enabled=false", "lms.ocr.provider=disabled",
    "lms.admin-bootstrap.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DB_URL",
    matches = "jdbc:mysql://[^/?#]+/learning_material_auth_test(?:_[A-Za-z0-9_]+)?(?:\\?[^#]*)?")
class SystemAcceptanceIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskLeaseService leases;
    @Autowired private MockTaskProcessor processor;
    private final List<Long> users = new ArrayList<>();

    @Test
    void twoSessionsCompleteNoteToReviewedCardFlowAndDeletedSourcesHideQuotations() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "d24_accept_" + suffix, password = "Safe_" + suffix;
        long ownerId = register(username, password);
        register("d24_other_" + suffix, password);
        String web = login(username, password, "WEB");
        String mini = login(username, password, "MINIPROGRAM");
        String other = login("d24_other_" + suffix, password, "WEB");
        assertThat(web).isNotEqualTo(mini);

        JsonNode material = data(exchange("/materials", HttpMethod.POST, Map.of(
            "type", "NOTE", "title", "软件验收自编笔记", "noteText", "软件验收合成文字：本段仅用于验证系统流程。",
            "tags", List.of("软件验收")), mini), HttpStatus.CREATED);
        String materialId = material.path("id").asText();
        assertThat(materialId).matches("[1-9][0-9]*");
        assertThat(material.path("version").asInt()).isEqualTo(1);
        JsonNode persisted = get("/materials/" + materialId, web);
        assertThat(persisted.path("id")).isEqualTo(material.path("id"));
        assertThat(persisted.path("noteText")).isEqualTo(material.path("noteText"));
        assertThat(persisted.path("tags")).isEqualTo(material.path("tags"));
        assertThat(get("/materials/" + materialId, mini)).isEqualTo(persisted);

        String note = "软件验收合成文字：更新后应以版本二生成候选。人工核对后保存卡片，引用必须对应本段原文。";
        JsonNode updated = data(exchange("/materials/" + materialId, HttpMethod.PATCH,
            Map.of("version", 1, "title", "软件验收修订笔记", "noteText", note), web), HttpStatus.OK);
        assertThat(updated.path("version").asInt()).isEqualTo(2);
        JsonNode miniUpdated = get("/materials/" + materialId, mini);
        assertThat(miniUpdated.path("version").asInt()).isEqualTo(2);
        assertThat(miniUpdated.path("noteText").asText()).isEqualTo(note);
        assertThat(get("/materials/" + materialId, web)).isEqualTo(miniUpdated);
        var stale = exchange("/materials/" + materialId, HttpMethod.PATCH,
            Map.of("version", 1, "noteText", "旧会话不应覆盖修订内容"), mini);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().path("error").path("currentVersion").asInt()).isEqualTo(2);
        assertThat(get("/materials/" + materialId, web).path("noteText").asText()).isEqualTo(note);
        assertThat(exchange("/materials/" + materialId, HttpMethod.GET, null, other).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/materials/" + materialId, HttpMethod.PATCH,
            Map.of("version", 2, "title", "越权修改"), other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/materials", other).path("total").asInt()).isZero();

        JsonNode submitted = data(exchange("/ai-tasks", HttpMethod.POST, Map.of(
            "materialId", materialId, "materialVersion", 2, "requestKey", UUID.randomUUID().toString(),
            "goalType", "REVIEW_CARDS", "externalProcessingConsent", true), mini), HttpStatus.ACCEPTED);
        String taskId = submitted.path("id").asText();
        assertThat(submitted.path("status").asText()).isEqualTo("QUEUED");
        assertThat(get("/notifications", web).path("total").asInt()).isZero();
        assertThat(exchange("/ai-tasks/" + taskId + "/result", HttpMethod.GET, null, web).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        // Only worker dispatch is explicit; preparation, publication and notifications are real services.
        var lease = leases.claim();
        assertThat(lease).isNotNull();
        assertThat(lease.taskId()).isEqualTo(Long.parseLong(taskId));
        processor.execute(lease);
        JsonNode ready = get("/ai-tasks/" + taskId, web);
        assertThat(ready.path("status").asText()).isEqualTo("WAIT_CONFIRM");
        assertThat(ready.path("stepCount").asInt()).isEqualTo(3);
        assertThat(get("/ai-tasks/" + taskId, mini)).isEqualTo(ready);

        JsonNode notifications = get("/notifications", web);
        assertThat(notifications.path("total").asInt()).isEqualTo(1);
        JsonNode notification = notifications.path("items").get(0);
        assertThat(notification.path("type").asText()).isEqualTo("RESULT_READY");
        assertThat(notification.path("target").path("taskId").asText()).isEqualTo(taskId);
        assertThat(notification.path("target").path("type").asText()).isEqualTo("AI_TASK");
        assertThat(notification.path("isRead").asBoolean()).isFalse();
        assertThat(notification.path("channelStatus").asText()).isEqualTo("NOT_REQUESTED");
        assertThat(notification.path("content").asText()).doesNotContain(note);
        assertThat(get("/notifications", mini)).isEqualTo(notifications);
        String notificationId = notification.path("id").asText();
        data(exchange("/notifications/" + notificationId + "/read-state", HttpMethod.PUT,
            Map.of("isRead", true), mini), HttpStatus.OK);
        assertThat(get("/notifications", web).path("items").get(0).path("isRead").asBoolean()).isTrue();
        assertThat(get("/notifications?unreadOnly=true", web).path("total").asInt()).isZero();
        assertThat(get("/notifications", other).path("total").asInt()).isZero();
        assertThat(exchange("/notifications/" + notificationId + "/read-state", HttpMethod.PUT,
            Map.of("isRead", true), other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode candidate = get("/ai-tasks/" + taskId + "/result", mini);
        assertThat(candidate.path("generationMode").asText()).isEqualTo("MOCK");
        assertThat(candidate.path("sourceVersion").asInt()).isEqualTo(2);
        assertThat(candidate.path("warnings").toString()).contains("模拟生成");
        assertThat(candidate.path("cards").size()).isEqualTo(1);
        JsonNode sources = get("/ai-tasks/" + taskId + "/sources", web);
        assertThat(sources.size()).isEqualTo(1);
        JsonNode source = sources.get(0), citation = candidate.path("cards").get(0).path("sources").get(0);
        assertThat(source.path("content").asText()).isEqualTo(note);
        assertThat(source.path("materialVersion").asInt()).isEqualTo(2);
        assertThat(citation.path("chunkId").asText()).isEqualTo(source.path("chunkId").asText());
        assertThat(citation.path("quoteText").asText()).isEqualTo(note.substring(
            citation.path("startOffset").asInt(), citation.path("endOffset").asInt()));
        assertThat(get("/ai-tasks/" + taskId + "/sources", mini)).isEqualTo(sources);
        for (String path : List.of("/ai-tasks/" + taskId, "/ai-tasks/" + taskId + "/result",
            "/ai-tasks/" + taskId + "/sources"))
            assertThat(exchange(path, HttpMethod.GET, null, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("resultVersion", candidate.path("resultVersion").asInt())
            .put("sourceVersion", 2).put("summary", "软件验收人工核对摘要").put("tagMode", "MERGE");
        input.putArray("tags").add("已核对");
        ObjectNode reviewed = candidate.path("cards").get(0).deepCopy();
        reviewed.put("title", "软件验收人工核对卡片").put("content", "软件验收人工核对正文：跨会话内容应保持一致。");
        input.putArray("cards").add(reviewed);
        input.putArray("relations");
        assertThat(exchange("/ai-tasks/" + taskId + "/confirmation", HttpMethod.PUT, input, other).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode receipt = data(exchange("/ai-tasks/" + taskId + "/confirmation", HttpMethod.PUT, input, web), HttpStatus.OK);
        assertThat(data(exchange("/ai-tasks/" + taskId + "/confirmation", HttpMethod.PUT, input, mini), HttpStatus.OK))
            .isEqualTo(receipt);
        assertThat(receipt.path("cardIds").size()).isEqualTo(1);
        assertThat(receipt.path("resultingMaterialVersion").asInt()).isEqualTo(3);
        assertThat(get("/ai-tasks/" + taskId, mini).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_card WHERE user_id=?", Long.class, ownerId)).isEqualTo(1);
        JsonNode confirmedMaterial = get("/materials/" + materialId, mini);
        assertThat(confirmedMaterial.path("summary").asText()).isEqualTo("软件验收人工核对摘要");
        assertThat(confirmedMaterial.path("tags").toString()).contains("软件验收", "已核对");
        assertThat(get("/materials/" + materialId, web)).isEqualTo(confirmedMaterial);

        String cardId = receipt.path("cardIds").get(0).asText();
        JsonNode card = get("/cards/" + cardId, mini);
        assertThat(card.path("title").asText()).isEqualTo(reviewed.path("title").asText());
        assertThat(card.path("content").asText()).isEqualTo(reviewed.path("content").asText());
        assertThat(card.path("generationMode").asText()).isEqualTo("MOCK");
        assertThat(card.path("isEdited").asBoolean()).isTrue();
        assertThat(get("/cards/" + cardId, web)).isEqualTo(card);
        assertThat(get("/cards", mini).path("total").asInt()).isEqualTo(1);
        assertThat(get("/cards", web)).isEqualTo(get("/cards", mini));
        JsonNode edited = data(exchange("/cards/" + cardId, HttpMethod.PATCH,
            Map.of("version", 1, "content", "软件验收再次编辑正文"), mini), HttpStatus.OK);
        assertThat(edited.path("version").asInt()).isEqualTo(2);
        assertThat(get("/cards/" + cardId, web)).isEqualTo(edited);
        assertThat(edited.path("content").asText()).isEqualTo("软件验收再次编辑正文");
        assertThat(get("/cards", other).path("total").asInt()).isZero();
        for (String path : List.of("/cards/" + cardId, "/cards/" + cardId + "/sources"))
            assertThat(exchange(path, HttpMethod.GET, null, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode linkedSource = get("/cards/" + cardId + "/sources", web).path("items").get(0);
        assertThat(linkedSource.path("materialId").asText()).isEqualTo(materialId);
        assertThat(linkedSource.path("isAvailable").asBoolean()).isTrue();
        assertThat(linkedSource.path("quoteText")).isEqualTo(citation.path("quoteText"));
        assertThat(linkedSource.path("content").asText()).isEqualTo(note);
        assertThat(exchange("/materials/" + materialId + "?version=3", HttpMethod.DELETE, null, mini).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/materials/" + materialId, HttpMethod.GET, null, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode redacted = get("/cards/" + cardId + "/sources", mini).path("items").get(0);
        assertThat(redacted.path("isAvailable").asBoolean()).isFalse();
        assertThat(redacted.path("unavailableReason").asText()).isEqualTo("SOURCE_DELETED");
        assertThat(redacted.path("quoteText").isNull()).isTrue();
        assertThat(redacted.path("content").isNull()).isTrue();
        assertThat(get("/cards/" + cardId + "/sources", web).path("items").get(0)).isEqualTo(redacted);
        assertThat(get("/cards/" + cardId, web)).isEqualTo(edited);
    }

    private long register(String username, String password) {
        JsonNode user = data(exchange("/auth/registrations", HttpMethod.POST,
            Map.of("username", username, "password", password, "displayName", "软件验收合成账号"), null), HttpStatus.CREATED);
        long id = user.path("id").asLong();
        assertThat(id).isPositive();
        users.add(id);
        return id;
    }

    private String login(String username, String password, String client) {
        String token = data(exchange("/auth/sessions", HttpMethod.POST,
            Map.of("username", username, "password", password, "clientType", client), null), HttpStatus.CREATED)
            .path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private JsonNode get(String path, String token) { return data(exchange(path, HttpMethod.GET, null, token), HttpStatus.OK); }

    private JsonNode data(ResponseEntity<JsonNode> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).as("HTTP response: %s", response.getBody()).isEqualTo(expected);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().path("data");
    }

    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange("http://127.0.0.1:" + port + "/api/v1" + path,
            method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    @AfterEach
    void cleanOwnRows() {
        for (long userId : users) {
            jdbc.update("DELETE FROM favorite WHERE user_id=?", userId);
            jdbc.update("DELETE FROM card_relation WHERE user_id=?", userId);
            jdbc.update("DELETE FROM card_source WHERE card_id IN (SELECT id FROM knowledge_card WHERE user_id=?)", userId);
            jdbc.update("UPDATE knowledge_card SET parent_card_id=NULL WHERE user_id=?", userId);
            jdbc.update("DELETE FROM knowledge_card WHERE user_id=?", userId);
            jdbc.update("DELETE FROM notification WHERE user_id=?", userId);
            jdbc.update("DELETE FROM ai_result WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", userId);
            jdbc.update("DELETE FROM ai_tool_call WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", userId);
            jdbc.update("DELETE FROM ai_task WHERE user_id=?", userId);
            jdbc.update("DELETE FROM material_chunk WHERE material_id IN (SELECT id FROM material WHERE user_id=?)", userId);
            jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON m.id=mt.material_id WHERE m.user_id=?", userId);
            jdbc.update("DELETE FROM material WHERE user_id=?", userId);
            jdbc.update("DELETE FROM tag WHERE user_id=?", userId);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", userId);
            jdbc.update("DELETE FROM app_user WHERE id=?", userId);
        }
    }
}
