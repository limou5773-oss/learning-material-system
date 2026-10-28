package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learningmaterial.system.service.TaskLeaseService;
import com.learningmaterial.system.task.MockTaskProcessor;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "lms.task.processor.mode=mock")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class ConfirmationIntegrationTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskLeaseService leases;
    @Autowired MockTaskProcessor processor;

    @Test
    void editedSubsetIsSavedAtomicallyAndRepeatedConfirmationIsIdempotent() {
        try (Fixture f = new Fixture()) {
            ObjectNode input = f.input();
            assertThat(f.candidate.path("cards").size()).isGreaterThan(1);
            ((ObjectNode) input.path("cards").get(0)).put("title", "用户核对后的标题");
            var response = f.confirm(input);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            JsonNode result = response.getBody().path("data");
            assertThat(result.path("cardIds").size()).isEqualTo(1);
            assertThat(result.path("resultingMaterialVersion").asInt()).isEqualTo(2);
            assertThat(f.confirm(input).getBody().path("data")).isEqualTo(result);
            String id = result.path("cardIds").get(0).asText();
            JsonNode card = f.get("/cards/" + id).path("data");
            assertThat(card.path("title").asText()).isEqualTo("用户核对后的标题");
            assertThat(card.path("generationMode").asText()).isEqualTo("MOCK");
            assertThat(card.path("isEdited").asBoolean()).isTrue();
            assertThat(f.get("/cards").path("data").path("total").asInt()).isEqualTo(1);
            assertThat(exchange("/cards/" + id, HttpMethod.GET, null, f.miniToken).getBody().path("data")).isEqualTo(card);
            assertThat(exchange("/cards", HttpMethod.GET, null, f.miniToken).getBody().path("data").path("total").asInt()).isEqualTo(1);
            JsonNode material = f.get("/materials/" + f.materialId).path("data");
            assertThat(material.path("summary").asText()).isEqualTo("核对后的摘要");
            assertThat(material.path("tags").toString()).contains("原标签", "新标签");
            JsonNode source = f.get("/cards/" + id + "/sources").path("data").path("items").get(0);
            assertThat(source.path("isAvailable").asBoolean()).isTrue();
            assertThat(source.path("content").asText()).contains(source.path("quoteText").asText());
            jdbc.update("UPDATE material SET access_state='RESTRICTED' WHERE id=?", f.materialId);
            source = f.get("/cards/" + id + "/sources").path("data").path("items").get(0);
            assertThat(source.path("isAvailable").asBoolean()).isFalse();
            assertThat(source.path("quoteText").isNull()).isTrue();
            assertThat(source.path("content").isNull()).isTrue();
            jdbc.update("UPDATE material SET status='DELETED' WHERE id=?", f.materialId);
            assertThat(f.get("/cards/" + id + "/sources").path("data").path("items").get(0)
                .path("unavailableReason").asText()).isEqualTo("SOURCE_DELETED");
        }
    }

    @Test
    void concurrentConfirmationReturnsExactlyTheSameReceipt() throws Exception {
        try (Fixture f = new Fixture(); ExecutorService pool = Executors.newFixedThreadPool(2)) {
            ObjectNode input = f.input();
            CountDownLatch start = new CountDownLatch(1);
            Callable<ResponseEntity<JsonNode>> call = () -> { start.await(); return f.confirm(input); };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            var first = a.get(20, TimeUnit.SECONDS); var second = b.get(20, TimeUnit.SECONDS);
            assertThat(first.getStatusCode().value()).isEqualTo(200);
            assertThat(second.getStatusCode().value()).isEqualTo(200);
            assertThat(first.getBody().path("data")).isEqualTo(second.getBody().path("data"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_card WHERE user_id=?", Long.class, f.userId)).isEqualTo(1);
        }
    }

    @Test
    void staleSourceCommitsInvalidationWithoutSavingCards() {
        try (Fixture f = new Fixture()) {
            jdbc.update("UPDATE material SET version=version+1 WHERE id=?", f.materialId);
            var response = f.confirm(f.input());
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody().path("error").path("code").asText()).isEqualTo("SOURCE_VERSION_CHANGED");
            assertThat(jdbc.queryForObject("SELECT status FROM ai_task WHERE id=?", String.class, f.taskId)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT status FROM ai_result WHERE task_id=?", String.class, f.taskId)).isEqualTo("INVALIDATED");
        }
    }

    @Test
    void rejectsInventedCandidatesForeignSourcesAndCancelledTasks() {
        try (Fixture f = new Fixture(); Fixture other = new Fixture()) {
            assertThat(exchange("/ai-tasks/" + f.taskId + "/confirmation", HttpMethod.PUT, f.input(), other.token)
                .getStatusCode().value()).isEqualTo(404);
            ObjectNode bad = f.input();
            ((ObjectNode)bad.path("cards").get(0)).put("candidateKey", "invented");
            assertThat(f.confirm(bad).getStatusCode().value()).isEqualTo(422);
            bad = f.input();
            ((ObjectNode)bad.path("cards").get(0).path("sources").get(0)).put("chunkId",
                other.input().path("cards").get(0).path("sources").get(0).path("chunkId").asText());
            assertThat(f.confirm(bad).getStatusCode().value()).isEqualTo(422);
            exchange("/ai-tasks/" + f.taskId + "/cancellations", HttpMethod.POST, null, f.token);
            assertThat(f.confirm(f.input()).getStatusCode().value()).isEqualTo(409);
        }
    }

    @Test
    void databaseFailureAfterCardInsertionRollsBackEveryWrite() {
        try (Fixture f = new Fixture()) {
            String trigger = "fail_confirmation_" + f.userId;
            jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON ai_result FOR EACH ROW BEGIN IF NEW.status='CONFIRMED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='induced confirmation failure'; END IF; END");
            try {
                assertThat(f.confirm(f.input()).getStatusCode().value()).isEqualTo(500);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_card WHERE user_id=?", Long.class, f.userId)).isZero();
                assertThat(jdbc.queryForObject("SELECT version FROM material WHERE id=?", Integer.class, f.materialId)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT summary FROM material WHERE id=?", String.class, f.materialId)).isNull();
                assertThat(jdbc.queryForObject("SELECT status FROM ai_result WHERE task_id=?", String.class, f.taskId)).isEqualTo("CANDIDATE");
                assertThat(jdbc.queryForObject("SELECT status FROM ai_task WHERE id=?", String.class, f.taskId)).isEqualTo("WAIT_CONFIRM");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag WHERE user_id=? AND name='新标签'", Long.class, f.userId)).isZero();
                assertThat(f.get("/materials/" + f.materialId).path("data").path("tags").toString()).contains("原标签").doesNotContain("新标签");
            } finally { jdbc.execute("DROP TRIGGER " + trigger); }
            assertThat(f.confirm(f.input()).getStatusCode().value()).isEqualTo(200);
        }
    }

    @Test
    void savesSelectedParentAndUserConfirmedRelationAndReplacesTags() {
        try (Fixture f = new Fixture()) {
            ObjectNode input = f.input();
            input.put("tagMode", "REPLACE");
            ObjectNode second = f.candidate.path("cards").get(1).deepCopy();
            second.put("parentCandidateKey", "mock-1"); input.withArray("cards").add(second);
            addRelation(input, "mock-2", null, null);
            JsonNode receipt = f.confirm(input).getBody().path("data");
            assertThat(receipt.path("cardIds").size()).isEqualTo(2);
            String firstId = receipt.path("cardIds").get(0).asText();
            assertThat(f.get("/cards/" + receipt.path("cardIds").get(1).asText()).path("data").path("parentCardId").asText()).isEqualTo(firstId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_relation WHERE user_id=? AND origin='USER_CONFIRMED'", Long.class, f.userId)).isEqualTo(1);
            assertThat(f.get("/materials/" + f.materialId).path("data").path("tags").toString()).contains("新标签").doesNotContain("原标签");
        }
    }

    @Test
    void validatesExistingRelationOwnershipDeletionAndVersion() {
        try (Fixture f = new Fixture(); Fixture stranger = new Fixture()) {
            String ownCard = f.confirm(f.input()).getBody().path("data").path("cardIds").get(0).asText();
            String otherCard = stranger.confirm(stranger.input()).getBody().path("data").path("cardIds").get(0).asText();
            assertThat(exchange("/cards/" + ownCard, HttpMethod.GET, null, stranger.token).getStatusCode().value()).isEqualTo(404);
            assertThat(exchange("/cards/" + ownCard + "/sources", HttpMethod.GET, null, stranger.token).getStatusCode().value()).isEqualTo(404);
            f.prepareTask(2);
            ObjectNode input = f.input(); addRelation(input, null, otherCard, 1);
            var invisible = f.confirm(input);
            assertThat(invisible.getStatusCode().value()).isEqualTo(404);
            assertThat(invisible.getBody().path("error").path("code").asText()).isEqualTo("CARD_NOT_FOUND");
            input = f.input(); addRelation(input, null, Long.toString(Long.MAX_VALUE), 1);
            var missing = f.confirm(input);
            assertThat(missing.getStatusCode().value()).isEqualTo(404);
            assertThat(missing.getBody().path("error")).isEqualTo(invisible.getBody().path("error"));
            input = f.input(); addRelation(input, null, ownCard, 2);
            assertThat(f.confirm(input).getStatusCode().value()).isEqualTo(409);
            jdbc.update("UPDATE knowledge_card SET status='DELETED' WHERE id=?", ownCard);
            input = f.input(); addRelation(input, null, ownCard, 1);
            var deleted = f.confirm(input);
            assertThat(deleted.getStatusCode().value()).isEqualTo(404);
            assertThat(deleted.getBody().path("error")).isEqualTo(invisible.getBody().path("error"));
            jdbc.update("UPDATE knowledge_card SET status='ACTIVE' WHERE id=?", ownCard);
            assertThat(f.confirm(input).getStatusCode().value()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_relation WHERE user_id=?", Long.class, f.userId)).isEqualTo(1);
        }
    }

    @Test
    void rejectsUnselectedRelationEndpointAndParentCyclesAndUnavailableMaterial() {
        try (Fixture f = new Fixture()) {
            ObjectNode input = f.input(); addRelation(input, "mock-2", null, null);
            assertThat(f.confirm(input).getStatusCode().value()).isEqualTo(422);
            input = f.input(); ObjectNode second = f.candidate.path("cards").get(1).deepCopy();
            ((ObjectNode)input.path("cards").get(0)).put("parentCandidateKey", "mock-2");
            second.put("parentCandidateKey", "mock-1"); input.withArray("cards").add(second);
            assertThat(f.confirm(input).getStatusCode().value()).isEqualTo(422);
            jdbc.update("UPDATE material SET access_state='RESTRICTED' WHERE id=?", f.materialId);
            assertThat(f.confirm(f.input()).getStatusCode().value()).isEqualTo(403);
            jdbc.update("UPDATE material SET access_state='NORMAL',status='DELETED' WHERE id=?", f.materialId);
            assertThat(f.confirm(f.input()).getStatusCode().value()).isEqualTo(409);
        }
    }

    @Test
    void relatedRelationsUseAscendingIdsWhileDirectedRelationsKeepTheirDirection() {
        try (Fixture f = new Fixture()) {
            String existing = f.confirm(f.input()).getBody().path("data").path("cardIds").get(0).asText();
            f.prepareTask(2);
            ObjectNode input = f.input(); addRelation(input, null, existing, 1);
            ObjectNode directed = input.path("relations").get(0).deepCopy();
            directed.put("relationKey", "r2").put("type", "PREREQUISITE");
            input.withArray("relations").add(directed);
            var response = f.confirm(input);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            long created = response.getBody().path("data").path("cardIds").get(0).asLong();
            assertThat(jdbc.queryForObject("SELECT source_card_id FROM card_relation WHERE user_id=? AND relation_type='RELATED'",
                Long.class, f.userId)).isEqualTo(Long.parseLong(existing));
            assertThat(jdbc.queryForObject("SELECT source_card_id FROM card_relation WHERE user_id=? AND relation_type='PREREQUISITE'",
                Long.class, f.userId)).isEqualTo(created);
        }
    }

    @Test
    void rejectsReverseDuplicateRelatedRelationsBeforeWriting() {
        try (Fixture f = new Fixture()) {
            ObjectNode input = f.input();
            input.withArray("cards").add(f.candidate.path("cards").get(1).deepCopy());
            addRelation(input, "mock-2", null, null);
            ObjectNode reverse = input.path("relations").get(0).deepCopy();
            reverse.put("relationKey", "r2");
            reverse.putObject("source").put("candidateKey", "mock-2");
            reverse.putObject("target").put("candidateKey", "mock-1");
            input.withArray("relations").add(reverse);
            var response = f.confirm(input);
            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getBody().path("error").path("code").asText()).isEqualTo("DUPLICATE_RELATION");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_card WHERE user_id=?", Long.class, f.userId)).isZero();
        }
    }

    private void addRelation(ObjectNode input, String candidate, String cardId, Integer version) {
        ObjectNode relation = input.withArray("relations").addObject();
        relation.put("relationKey", "r1").put("type", "RELATED").put("evidenceText", "用户核对的关联依据");
        relation.putObject("source").put("candidateKey", "mock-1");
        ObjectNode target = relation.putObject("target");
        if (candidate != null) target.put("candidateKey", candidate);
        else target.put("cardId", cardId).put("cardVersion", version);
    }

    @Test
    void validatesNestedInputSourcesParentsAndRelationsBeforeWriting() {
        try (Fixture f = new Fixture()) {
            List<ObjectNode> invalid = new ArrayList<>();
            ObjectNode bad = f.input(); bad.putNull("summary"); invalid.add(bad);
            bad = f.input(); bad.putArray("cards"); invalid.add(bad);
            bad = f.input(); bad.withArray("cards").add(bad.path("cards").get(0).deepCopy()); invalid.add(bad);
            bad = f.input(); ((ObjectNode) bad.path("cards").get(0)).put("parentCandidateKey", "mock-1"); invalid.add(bad);
            bad = f.input(); ((ObjectNode) bad.path("cards").get(0).path("sources").get(0)).put("quoteText", "伪造引用"); invalid.add(bad);
            bad = f.input(); ((ObjectNode) bad.path("cards").get(0)).withArray("sources")
                .add(bad.path("cards").get(0).path("sources").get(0).deepCopy()); invalid.add(bad);
            bad = f.input(); bad.withArray("tags").add("新标签"); invalid.add(bad);
            bad = f.input(); bad.withArray("relations").addObject().put("relationKey", "r1").put("type", "RELATED")
                .put("evidenceText", "关联依据").putObject("source").put("candidateKey", "mock-1"); invalid.add(bad);
            for (ObjectNode input : invalid) assertThat(f.confirm(input).getStatusCode().value()).as(input.toString()).isEqualTo(422);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_card WHERE user_id=?", Long.class, f.userId)).isZero();
        }
    }

    private class Fixture implements AutoCloseable {
        long userId; String token, miniToken, materialId, taskId; JsonNode candidate;
        Fixture() {
            String name = "confirm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String password = "Safe_" + name;
            userId = rest.postForEntity(url("/auth/registrations"), Map.of("username", name, "password", password,
                "displayName", name), JsonNode.class).getBody().path("data").path("id").asLong();
            token = rest.postForEntity(url("/auth/sessions"), Map.of("username", name, "password", password,
                "clientType", "WEB"), JsonNode.class).getBody().path("data").path("accessToken").asText();
            miniToken = rest.postForEntity(url("/auth/sessions"), Map.of("username", name, "password", password,
                "clientType", "MINIPROGRAM"), JsonNode.class).getBody().path("data").path("accessToken").asText();
            materialId = exchange("/materials", HttpMethod.POST, Map.of("type", "NOTE", "title", "测试笔记",
                "noteText", "真实原文。".repeat(180), "tags", List.of("原标签")), token).getBody().path("data").path("id").asText();
            prepareTask(1);
        }
        void prepareTask(int version) {
            taskId = exchange("/ai-tasks", HttpMethod.POST, Map.of("materialId", materialId, "materialVersion", version,
                "requestKey", "confirm-" + version, "goalType", "REVIEW_CARDS", "externalProcessingConsent", true), token)
                .getBody().path("data").path("id").asText();
            var lease = leases.claim(); assertThat(lease.taskId()).isEqualTo(Long.parseLong(taskId)); processor.execute(lease);
            candidate = get("/ai-tasks/" + taskId + "/result").path("data");
        }
        ObjectNode input() {
            ObjectNode node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            node.put("resultVersion", candidate.path("resultVersion").asInt()).put("sourceVersion", candidate.path("sourceVersion").asInt()).put("summary", "核对后的摘要").put("tagMode", "MERGE");
            node.putArray("tags").add("新标签"); node.putArray("cards").add(candidate.path("cards").get(0).deepCopy());
            node.putArray("relations"); return node;
        }
        JsonNode get(String path) { return exchange(path, HttpMethod.GET, null, token).getBody(); }
        ResponseEntity<JsonNode> confirm(Object body) { return exchange("/ai-tasks/" + taskId + "/confirmation", HttpMethod.PUT, body, token); }
        public void close() {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_card'", Integer.class) > 0) {
                jdbc.update("DELETE FROM card_relation WHERE user_id=?", userId);
                jdbc.update("DELETE FROM card_source WHERE card_id IN (SELECT id FROM knowledge_card WHERE user_id=?)", userId);
                jdbc.update("UPDATE knowledge_card SET parent_card_id=NULL WHERE user_id=?", userId);
                jdbc.update("DELETE FROM knowledge_card WHERE user_id=?", userId);
            }
            jdbc.update("DELETE FROM ai_result WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", userId);
            jdbc.update("DELETE FROM ai_tool_call WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", userId);
            jdbc.update("DELETE FROM ai_task WHERE user_id=?", userId);
            jdbc.update("DELETE FROM material_chunk WHERE material_id IN (SELECT id FROM material WHERE user_id=?)", userId);
            jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON m.id=mt.material_id WHERE m.user_id=?", userId);
            jdbc.update("DELETE FROM material WHERE user_id=?", userId); jdbc.update("DELETE FROM tag WHERE user_id=?", userId);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", userId); jdbc.update("DELETE FROM app_user WHERE id=?", userId);
        }
    }
    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(token);
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
