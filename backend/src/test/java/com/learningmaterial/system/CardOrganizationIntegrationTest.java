package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learningmaterial.system.task.MockTaskProcessor;
import com.learningmaterial.system.task.TaskLeaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Connection;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "lms.task.processor.mode=mock")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class CardOrganizationIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private TaskLeaseService leases;
    @Autowired private MockTaskProcessor processor;
    private long userId, otherId;
    private String web, mini, other;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Safe_" + suffix;
        userId = register("org_" + suffix, password);
        otherId = register("orgother_" + suffix, password);
        web = login("org_" + suffix, password, "WEB");
        mini = login("org_" + suffix, password, "MINIPROGRAM");
        other = login("orgother_" + suffix, password, "WEB");
    }

    @AfterEach
    void clean() {
        for (long id : List.of(userId, otherId)) {
            jdbc.update("DELETE FROM card_layout WHERE user_id=?", id);
            jdbc.update("DELETE FROM card_organization_lock WHERE user_id=?", id);
            jdbc.update("DELETE FROM favorite WHERE user_id=?", id);
            jdbc.update("DELETE FROM card_relation WHERE user_id=?", id);
            jdbc.update("DELETE FROM card_source WHERE card_id IN (SELECT id FROM knowledge_card WHERE user_id=?)", id);
            jdbc.update("UPDATE knowledge_card SET parent_card_id=NULL WHERE user_id=?", id);
            jdbc.update("DELETE FROM knowledge_card WHERE user_id=?", id);
            jdbc.update("DELETE FROM ai_result WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", id);
            jdbc.update("DELETE FROM ai_tool_call WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", id);
            jdbc.update("DELETE FROM ai_task WHERE user_id=?", id);
            jdbc.update("DELETE FROM material_chunk WHERE material_id IN (SELECT id FROM material WHERE user_id=?)", id);
            jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON m.id=mt.material_id WHERE m.user_id=?", id);
            jdbc.update("DELETE FROM material WHERE user_id=?", id);
            jdbc.update("DELETE FROM tag WHERE user_id=?", id);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", id);
            jdbc.update("DELETE FROM app_user WHERE id=?", id);
        }
    }

    @Test
    void layoutStartsAtZeroReplacesPersistsAndClearsWithoutChangingCardVersion() {
        assertThat(exchange("/card-layouts/default", HttpMethod.GET, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode empty = data(exchange("/card-layouts/default", HttpMethod.GET, null, web));
        assertThat(empty.path("layoutKey").asText()).isEqualTo("default");
        assertThat(empty.path("layoutVersion").asInt()).isZero();
        assertThat(empty.path("items").size()).isZero();
        String a = create(web), b = create(web);
        List<Map<String, Object>> items = List.of(item(b, -100000, 100000, Integer.MAX_VALUE), item(a, 12.25, -6.5, 0));
        JsonNode saved = save("default", 0, items, web);
        assertThat(saved.path("layoutVersion").asInt()).isEqualTo(1);
        assertThat(data(exchange("/card-layouts/default", HttpMethod.GET, null, mini))).isEqualTo(saved);
        assertThat(saved.path("items").get(1).path("x").asDouble()).isEqualTo(12.25);
        assertThat(data(exchange("/cards/" + a, HttpMethod.GET, null, web)).path("version").asInt()).isEqualTo(1);
        assertThat(save("default", 1, List.of(item(a, 1, 2, 0)), mini).path("layoutVersion").asInt()).isEqualTo(2);
        assertThat(save("default", 2, List.of(), web).path("items").size()).isZero();
        assertThat(data(exchange("/card-layouts/default", HttpMethod.GET, null, web)).path("layoutVersion").asInt()).isEqualTo(3);
    }

    @Test
    void layoutKeysAreCaseSensitiveAndAccountsCannotShareSnapshots() {
        String card = create(web), foreign = create(other);
        save("Case", 0, List.of(item(card, 1, 2, 0)), web);
        assertThat(data(exchange("/card-layouts/case", HttpMethod.GET, null, web)).path("layoutVersion").asInt()).isZero();
        save("case", 0, List.of(), web);
        assertThat(data(exchange("/card-layouts/Case", HttpMethod.GET, null, other)).path("layoutVersion").asInt()).isZero();
        save("Case", 0, List.of(item(foreign, 3, 4, 0)), other);
        assertThat(data(exchange("/card-layouts/Case", HttpMethod.GET, null, web)).path("items").get(0).path("cardId").asText()).isEqualTo(card);
        save("a".repeat(64), 0, List.of(), web);
        for (String key : List.of("bad.key", "a".repeat(65)))
            assertThat(exchange("/card-layouts/" + key, HttpMethod.GET, null, web).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void missingAndStaleLayoutVersionsReportTheCurrentVersion() {
        assertConflict(exchange("/card-layouts/new", HttpMethod.PUT, Map.of("layoutVersion", 1, "items", List.of()), web), "LAYOUT_VERSION_CONFLICT", 0);
        save("default", 0, List.of(), web);
        assertConflict(exchange("/card-layouts/default", HttpMethod.PUT, Map.of("layoutVersion", 0, "items", List.of()), mini), "LAYOUT_VERSION_CONFLICT", 1);
    }

    @Test
    void simultaneousFirstAndExistingLayoutSavesHaveExactlyOneWinner() throws Exception {
        String a = create(web), b = create(web);
        for (int version : List.of(0, 1)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = after(start, "/card-layouts/race", Map.of("layoutVersion", version, "items", List.of(item(a, 10, 10, 0))), web);
            var second = after(start, "/card-layouts/race", Map.of("layoutVersion", version, "items", List.of(item(b, 20, 20, 0))), mini);
            start.countDown();
            var one = first.get(10, TimeUnit.SECONDS); var two = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(one.getStatusCode(), two.getStatusCode())).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            assertConflict(one.getStatusCode() == HttpStatus.CONFLICT ? one : two, "LAYOUT_VERSION_CONFLICT", version + 1);
            JsonNode winner = data(exchange("/card-layouts/race", HttpMethod.GET, null, web));
            assertThat(winner.path("layoutVersion").asInt()).isEqualTo(version + 1);
            assertThat(winner.path("items").size()).isEqualTo(1);
            assertThat(winner.path("items").get(0).path("cardId").asText()).isIn(a, b);
        }
    }

    @Test
    void invalidReferencesRejectTheWholeSnapshotAndGetPreservesHistoricalReferences() {
        String a = create(web), deleted = create(web), foreign = create(other);
        JsonNode before = save("default", 0, List.of(item(a, 1, 2, 0)), web);
        jdbc.update("UPDATE knowledge_card SET status='DELETED' WHERE id=?", deleted);
        for (String invalid : List.of(deleted, foreign, "9223372036854775807")) {
            assertThat(exchange("/card-layouts/default", HttpMethod.PUT, Map.of("layoutVersion", 1, "items", List.of(item(a, 5, 6, 0), item(invalid, 7, 8, 1))), web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(data(exchange("/card-layouts/default", HttpMethod.GET, null, web))).isEqualTo(before);
        }
        jdbc.update("UPDATE knowledge_card SET status='DELETED' WHERE id=?", a);
        assertThat(data(exchange("/card-layouts/default", HttpMethod.GET, null, web))).isEqualTo(before);
        save("default", 1, List.of(), web);
    }

    @Test
    void layoutValidatesRequiredBoundsUniquenessAndExactJsonTypes() {
        String a = create(web), b = create(web);
        List<Map<String, Object>> badItems = List.of(item(a, 100001, 0, 0), item(a, 0, -100001, 0), item(a, 0, 0, -1));
        for (var item : badItems) assertThat(exchange("/card-layouts/default", HttpMethod.PUT, Map.of("layoutVersion", 0, "items", List.of(item)), web).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        for (Object body : List.of(Map.of("items", List.of()), Map.of("layoutVersion", -1, "items", List.of()), Map.of("layoutVersion", 0), Map.of("layoutVersion", 0, "items", List.of(item(a, 0, 0, 0), item(a, 1, 1, 1))), Map.of("layoutVersion", 0, "items", List.of(item(a, 0, 0, 0), item(b, 1, 1, 0))), Map.of("layoutVersion", 0, "items", Collections.nCopies(201, item(a, 0, 0, 0)))))
            assertThat(exchange("/card-layouts/default", HttpMethod.PUT, body, web).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        for (String body : List.of(
            "{\"layoutVersion\":\"0\",\"items\":[]}", "{\"layoutVersion\":0.5,\"items\":[]}",
            "{\"layoutVersion\":0,\"items\":[],\"userId\":1}",
            "{\"layoutVersion\":0,\"items\":[{\"cardId\":" + a + ",\"x\":0,\"y\":0,\"orderNo\":0}]}",
            "{\"layoutVersion\":0,\"items\":[{\"cardId\":\"" + a + "\",\"x\":\"1\",\"y\":0,\"orderNo\":0}]}",
            "{\"layoutVersion\":0,\"items\":[{\"cardId\":\"" + a + "\",\"x\":0,\"y\":0,\"orderNo\":0.2}]}",
            "{\"layoutVersion\":0,\"items\":[{\"cardId\":\"" + a + "\",\"x\":0,\"y\":0,\"orderNo\":2147483648}]}",
            "{\"layoutVersion\":0,\"items\":[{\"cardId\":\"" + a + "\",\"x\":NaN,\"y\":0,\"orderNo\":0}]}"))
            assertThat(exchange("/card-layouts/default", HttpMethod.PUT, body, web).getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(data(exchange("/card-layouts/default", HttpMethod.GET, null, web)).path("layoutVersion").asInt()).isZero();
    }

    @Test
    void exactlyTwoHundredOwnedCardsCanBeStored() {
        String seed = create(web);
        for (int i = 1; i < 200; i++) jdbc.update("INSERT INTO knowledge_card(user_id,title,content,tags_json,version,origin,generation_mode,is_edited,status,created_at,updated_at) SELECT user_id,title,content,tags_json,version,origin,generation_mode,is_edited,status,created_at,updated_at FROM knowledge_card WHERE id=?", seed);
        List<Long> ids = jdbc.queryForList("SELECT id FROM knowledge_card WHERE user_id=? ORDER BY id", Long.class, userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) items.add(item(ids.get(i).toString(), i, -i, i));
        assertThat(save("default", 0, items, web).path("items").size()).isEqualTo(200);
    }

    @Test
    void parentUpdatesRequireVersionAndExplicitParentAndPreserveCardMetadata() {
        String a = create(web), b = create(web);
        jdbc.update("UPDATE knowledge_card SET updated_at='2025-01-01 00:00:00' WHERE id=?", a);
        JsonNode before = data(exchange("/cards/" + a, HttpMethod.GET, null, web));
        JsonNode linked = data(parent(a, 1, b, mini));
        assertThat(linked.path("parentCardId").asText()).isEqualTo(b);
        assertThat(linked.path("version").asInt()).isEqualTo(2);
        assertThat(linked.path("updatedAt")).isNotEqualTo(before.path("updatedAt"));
        for (String field : List.of("title", "content", "summary", "subject", "tags", "origin", "generationMode", "isEdited", "createdAt")) assertThat(linked.path(field)).as(field).isEqualTo(before.path(field));
        assertConflict(parent(a, 1, null, web), "VERSION_CONFLICT", 2);
        assertThat(data(parent(a, 2, null, web)).path("parentCardId").isNull()).isTrue();
        for (Object body : List.of(Map.of("version", 3), Map.of("parentCardId", b), Map.of("version", 0, "parentCardId", b)))
            assertThat(exchange("/cards/" + a + "/parent", HttpMethod.PUT, body, web).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        for (String body : List.of("{\"version\":3,\"parentCardId\":null,\"content\":\"forbidden\"}", "{\"version\":\"3\",\"parentCardId\":null}", "{\"version\":3.5,\"parentCardId\":null}", "{\"version\":3,\"parentCardId\":" + b + "}"))
            assertThat(exchange("/cards/" + a + "/parent", HttpMethod.PUT, body, web).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(data(exchange("/cards/" + a, HttpMethod.GET, null, web)).path("version").asInt()).isEqualTo(3);
    }

    @Test
    void parentRejectsSelfIndirectCyclesAndForeignDeletedOrBrokenAncestors() {
        String a = create(web), b = create(web), c = create(web), foreign = create(other);
        assertCycle(parent(a, 1, a, web));
        data(parent(a, 1, b, web)); data(parent(b, 1, c, web));
        assertCycle(parent(c, 1, a, web));
        assertThat(parent(c, 1, foreign, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parent(a, 2, b, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        jdbc.update("UPDATE knowledge_card SET status='DELETED' WHERE id=?", c);
        assertThat(parent(c, 1, null, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parent(a, 2, c, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parent(a, 2, b, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        jdbc.update("UPDATE knowledge_card SET status='ACTIVE',parent_card_id=? WHERE id=?", b, c);
        assertCycle(parent(a, 2, b, web));
        jdbc.update("UPDATE knowledge_card SET parent_card_id=? WHERE id=?", foreign, b);
        assertThat(parent(a, 2, b, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(data(exchange("/cards/" + a, HttpMethod.GET, null, web)).path("version").asInt()).isEqualTo(2);
    }

    @Test
    void concurrentMutualParentsCannotCreateACycleAndSameCardUpdatesHaveOneVersionWinner() throws Exception {
        String a = create(web), b = create(web), c = create(web);
        CountDownLatch start = new CountDownLatch(1);
        var first = after(start, "/cards/" + a + "/parent", Map.of("version", 1, "parentCardId", b), web);
        var second = after(start, "/cards/" + b + "/parent", Map.of("version", 1, "parentCardId", a), mini);
        start.countDown();
        var one = first.get(10, TimeUnit.SECONDS); var two = second.get(10, TimeUnit.SECONDS);
        assertThat(List.of(one.getStatusCode(), two.getStatusCode())).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY);
        assertCycle(one.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY ? one : two);
        CountDownLatch updateStart = new CountDownLatch(1);
        var updateOne = after(updateStart, "/cards/" + c + "/parent", Map.of("version", 1, "parentCardId", a), web);
        var updateTwo = after(updateStart, "/cards/" + c + "/parent", Map.of("version", 1, "parentCardId", b), mini);
        updateStart.countDown();
        one = updateOne.get(10, TimeUnit.SECONDS); two = updateTwo.get(10, TimeUnit.SECONDS);
        assertThat(List.of(one.getStatusCode(), two.getStatusCode())).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertConflict(one.getStatusCode() == HttpStatus.CONFLICT ? one : two, "VERSION_CONFLICT", 2);
    }

    @Test
    void reparentingGeneratedCardsRetainsSourcesAndGenerationMode() {
        String material = data(exchange("/materials", HttpMethod.POST, Map.of("type", "NOTE", "title", "来源", "noteText", "历史原文"), web)).path("id").asText();
        jdbc.update("INSERT INTO ai_task(user_id,material_id,material_version,request_key,request_hash,goal_type,status,step_count,cancel_flag,execution_epoch,config_json,created_at,updated_at) VALUES(?,?,1,'d18-fixture',REPEAT('a',64),'REVIEW_CARDS','SUCCEEDED',0,0,1,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", userId, material);
        long task = jdbc.queryForObject("SELECT MAX(id) FROM ai_task WHERE user_id=?", Long.class, userId);
        jdbc.update("INSERT INTO material_chunk(material_id,material_version,chunk_no,content,extraction_method,created_at) VALUES(?,1,1,'历史原文','TEXT',UTC_TIMESTAMP(6))", material);
        long chunk = jdbc.queryForObject("SELECT MAX(id) FROM material_chunk WHERE material_id=?", Long.class, material);
        String target = create(web);
        for (String mode : List.of("MOCK", "MODEL")) {
            jdbc.update("INSERT INTO knowledge_card(user_id,task_id,candidate_key,title,content,tags_json,version,origin,generation_mode,is_edited,status,created_at,updated_at) VALUES(?,?,?,'AI原题','历史原文',JSON_ARRAY('原签'),1,'AI',?,0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", userId, task, "d18-" + mode, mode);
            String card = jdbc.queryForObject("SELECT MAX(id) FROM knowledge_card WHERE user_id=?", Long.class, userId).toString();
            jdbc.update("INSERT INTO card_source(card_id,chunk_id,quote_text,start_offset,end_offset,created_at) VALUES(?,?,'历史原文',0,4,UTC_TIMESTAMP(6))", card, chunk);
            JsonNode before = data(exchange("/cards/" + card + "/sources", HttpMethod.GET, null, web));
            JsonNode result = data(parent(card, 1, target, web));
            assertThat(result.path("origin").asText()).isEqualTo("AI");
            assertThat(result.path("generationMode").asText()).isEqualTo(mode);
            assertThat(result.path("isEdited").asBoolean()).isFalse();
            assertThat(result.path("content").asText()).isEqualTo("历史原文");
            assertThat(result.path("tags").get(0).asText()).isEqualTo("原签");
            assertThat(data(exchange("/cards/" + card + "/sources", HttpMethod.GET, null, web))).isEqualTo(before);
        }
    }

    @Test
    void organizationWritesDoNotDeadlockConfirmationOfAnExistingRelatedCard() throws Exception {
        for (String operation : List.of("layout", "parent")) {
            String existing = create(web), target = create(web);
            String material = data(exchange("/materials", HttpMethod.POST, Map.of("type", "NOTE", "title", "并发确认", "noteText", "真实原文。".repeat(180)), web)).path("id").asText();
            String task = data(exchange("/ai-tasks", HttpMethod.POST, Map.of("materialId", material, "materialVersion", 1, "requestKey", "d18-" + operation, "goalType", "REVIEW_CARDS", "externalProcessingConsent", true), web)).path("id").asText();
            var lease = leases.claim(); assertThat(lease.taskId()).isEqualTo(Long.parseLong(task)); processor.execute(lease);
            JsonNode candidate = data(exchange("/ai-tasks/" + task + "/result", HttpMethod.GET, null, web));
            ObjectNode input = JsonNodeFactory.instance.objectNode();
            input.put("resultVersion", candidate.path("resultVersion").asInt()).put("sourceVersion", 1).put("summary", "核对摘要").put("tagMode", "MERGE");
            input.putArray("tags"); input.putArray("cards").add(candidate.path("cards").get(0).deepCopy());
            ObjectNode relation = input.putArray("relations").addObject();
            relation.put("relationKey", "r1").put("type", "RELATED").put("evidenceText", "核对后的关系依据");
            relation.putObject("source").put("candidateKey", candidate.path("cards").get(0).path("candidateKey").asText());
            relation.putObject("target").put("cardId", existing).put("cardVersion", 1);
            String gate = "d18_gate_" + userId + "_" + operation, trigger = "d18_gate_" + userId;
            CompletableFuture<ResponseEntity<JsonNode>> confirming = null, organizing = null;
            try (Connection blocker = dataSource.getConnection()) {
                try (var statement = blocker.prepareStatement("SELECT GET_LOCK(?,5)")) { statement.setString(1, gate); try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1); } }
                jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON knowledge_card FOR EACH ROW BEGIN IF NEW.user_id=" + userId + " AND NEW.origin='AI' THEN SET @d18_gate_result=GET_LOCK('" + gate + "',10); DO RELEASE_LOCK('" + gate + "'); END IF; END");
                try {
                    confirming = CompletableFuture.supplyAsync(() -> exchange("/ai-tasks/" + task + "/confirmation", HttpMethod.PUT, input, web));
                    awaitSql("SELECT COUNT(*) FROM performance_schema.metadata_locks WHERE OBJECT_TYPE='USER LEVEL LOCK' AND OBJECT_NAME=? AND LOCK_STATUS='PENDING'", gate);
                    organizing = CompletableFuture.supplyAsync(() -> operation.equals("layout") ? exchange("/card-layouts/concurrent", HttpMethod.PUT, Map.of("layoutVersion", 0, "items", List.of(item(existing, 1, 2, 0))), mini) : parent(existing, 1, target, mini));
                    awaitSql("SELECT COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_SCHEMA=DATABASE() AND OBJECT_NAME='knowledge_card' AND LOCK_STATUS='WAITING'");
                    try (var statement = blocker.prepareStatement("SELECT RELEASE_LOCK(?)")) { statement.setString(1, gate); statement.execute(); }
                    var confirmed = confirming.get(10, TimeUnit.SECONDS); var organized = organizing.get(10, TimeUnit.SECONDS);
                    if (confirmed.getStatusCode() != HttpStatus.OK || organized.getStatusCode() != HttpStatus.OK) {
                        String status = jdbc.queryForMap("SHOW ENGINE INNODB STATUS").get("Status").toString();
                        System.out.println("D18 confirmation/" + operation + " lock evidence: latest deadlock=" + status.contains("LATEST DETECTED DEADLOCK") + ", app_user=" + status.contains("app_user") + ", knowledge_card=" + status.contains("knowledge_card"));
                    }
                    assertThat(confirmed.getStatusCode()).as("confirmation concurrent with %s: %s", operation, confirmed.getBody()).isEqualTo(HttpStatus.OK);
                    assertThat(organized.getStatusCode()).as("organization %s: %s", operation, organized.getBody()).isEqualTo(HttpStatus.OK);
                } finally {
                    try (var statement = blocker.prepareStatement("SELECT RELEASE_LOCK(?)")) { statement.setString(1, gate); statement.execute(); }
                    if (confirming != null) confirming.get(15, TimeUnit.SECONDS);
                    if (organizing != null) organizing.get(15, TimeUnit.SECONDS);
                    jdbc.execute("DROP TRIGGER " + trigger);
                }
            }
        }
    }

    private void awaitSql(String sql, Object... args) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject(sql, Long.class, args) > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for controlled database lock: " + sql);
    }

    private Map<String, Object> item(String card, Number x, Number y, int order) { return Map.of("cardId", card, "x", x, "y", y, "orderNo", order); }
    private JsonNode save(String key, int version, Object items, String token) { return data(exchange("/card-layouts/" + key, HttpMethod.PUT, Map.of("layoutVersion", version, "items", items), token)); }
    private ResponseEntity<JsonNode> parent(String card, int version, String parent, String token) {
        Map<String, Object> body = new HashMap<>(); body.put("version", version); body.put("parentCardId", parent);
        return exchange("/cards/" + card + "/parent", HttpMethod.PUT, body, token);
    }
    private JsonNode data(ResponseEntity<JsonNode> response) { assertThat(response.getStatusCode()).as("response: %s", response.getBody()).isIn(HttpStatus.OK, HttpStatus.CREATED, HttpStatus.ACCEPTED); return response.getBody().path("data"); }
    private void assertConflict(ResponseEntity<JsonNode> response, String code, int version) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().path("error").path("code").asText()).isEqualTo(code);
        assertThat(response.getBody().path("error").path("currentVersion").asInt()).isEqualTo(version);
    }
    private void assertCycle(ResponseEntity<JsonNode> response) { assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY); assertThat(response.getBody().path("error").path("code").asText()).isEqualTo("PARENT_CYCLE"); }
    private CompletableFuture<ResponseEntity<JsonNode>> after(CountDownLatch start, String path, Object body, String token) {
        return CompletableFuture.supplyAsync(() -> { try { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); } return exchange(path, HttpMethod.PUT, body, token); });
    }
    private String create(String token) { return data(exchange("/cards", HttpMethod.POST, Map.of("title", "卡片", "content", "正文\n保留", "summary", "摘要", "subject", "数学", "tags", List.of("重点")), token)).path("id").asText(); }
    private long register(String username, String password) {
        ResponseEntity<JsonNode> response = rest.postForEntity(url("/auth/registrations"), Map.of("username", username, "password", password, "displayName", username), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); return response.getBody().path("data").path("id").asLong();
    }
    private String login(String username, String password, String type) {
        ResponseEntity<JsonNode> response = rest.postForEntity(url("/auth/sessions"), Map.of("username", username, "password", password, "clientType", type), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); return response.getBody().path("data").path("accessToken").asText();
    }
    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders(); if (token != null) headers.setBearerAuth(token); if (body instanceof String) headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(java.net.URI.create(url(path)), method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
