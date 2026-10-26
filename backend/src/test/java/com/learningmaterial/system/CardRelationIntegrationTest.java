package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learningmaterial.system.task.MockTaskProcessor;
import com.learningmaterial.system.task.TaskLeaseService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "lms.task.processor.mode=mock")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class CardRelationIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskLeaseService leases;
    @Autowired private MockTaskProcessor processor;
    private long userId, otherId;
    private String web, mini, other;

    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12), password = "Safe_" + suffix;
        userId = register("rel_" + suffix, password); otherId = register("relother_" + suffix, password);
        web = login("rel_" + suffix, password, "WEB"); mini = login("rel_" + suffix, password, "MINIPROGRAM");
        other = login("relother_" + suffix, password, "WEB");
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id=?", String.class, userId)).isEqualTo("ENABLED");
    }

    @AfterEach void clean() {
        for (long id : List.of(userId, otherId)) {
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

    @Test void createsCanonicalRelatedAndPreservesDirectedTypesAndCardVersions() {
        String a = create(web, "Math"), b = create(web, "Math");
        JsonNode related = data(post(b, 1, a, 1, "RELATED", "  人工核对说明  "));
        assertThat(related.properties()).hasSize(5);
        assertThat(related.path("sourceCardId").asText()).isEqualTo(a);
        assertThat(related.path("targetCardId").asText()).isEqualTo(b);
        assertThat(related.path("type").asText()).isEqualTo("RELATED");
        assertThat(related.path("evidenceText").asText()).isEqualTo("人工核对说明");
        for (String type : List.of("PREREQUISITE", "EXAMPLE")) {
            JsonNode directed = data(post(b, 1, a, 1, type, "方向明确"));
            assertThat(directed.path("sourceCardId").asText()).isEqualTo(b);
            assertThat(directed.path("targetCardId").asText()).isEqualTo(a);
            data(post(a, 1, b, 1, type, "反向也可保存"));
        }
        assertThat(data(get("/card-relations", mini)).path("total").asInt()).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT DISTINCT origin FROM card_relation WHERE user_id=?", String.class, userId)).containsExactly("USER_CONFIRMED");
        assertThat(data(get("/cards/" + a, web)).path("version").asInt()).isEqualTo(1);
        assertThat(data(get("/cards/" + b, web)).path("version").asInt()).isEqualTo(1);
    }

    @Test void rejectsWrongJsonTypesUnknownFieldsAndInvalidBusinessValues() {
        String a = create(web, "Math"), b = create(web, "Math");
        Map<String, Object> valid = body(a, 1, b, 1, "RELATED", "依据");
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of("sourceCardId", 1, "targetCardId", true,
            "sourceCardVersion", "1", "targetCardVersion", 1.0, "type", 1, "evidenceText", List.of("说明")).entrySet()) {
            Map<String, Object> input = new HashMap<>(valid); input.put(invalid.getKey(), invalid.getValue());
            assertThat(exchange("/card-relations", HttpMethod.POST, input, web).getStatusCode()).as(invalid.getKey()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> extra = new HashMap<>(valid); extra.put("origin", "MODEL");
        assertThat(exchange("/card-relations", HttpMethod.POST, extra, web).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (Map.Entry<String, Object> invalid : List.<Map.Entry<String, Object>>of(Map.entry("sourceCardId", "0"), Map.entry("targetCardId", "9223372036854775808"),
            Map.entry("sourceCardVersion", 0), Map.entry("targetCardVersion", -1), Map.entry("type", "related"),
            Map.entry("evidenceText", "  "), Map.entry("evidenceText", "文".repeat(2001)))) {
            Map<String, Object> input = new HashMap<>(valid); input.put(invalid.getKey(), invalid.getValue());
            assertThat(exchange("/card-relations", HttpMethod.POST, input, web).getStatusCode()).as(invalid.getKey()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Map<String, Object> missing = new HashMap<>(valid); missing.remove("targetCardVersion");
        assertThat(exchange("/card-relations", HttpMethod.POST, missing, web).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        data(post(a, 1, b, 1, "RELATED", "文".repeat(2000)));
    }

    @Test void checksOriginalSourceAndTargetVersionsBeforeCanonicalization() {
        String a = create(web, "Math"), b = create(web, "Math");
        jdbc.update("UPDATE knowledge_card SET version=2 WHERE id=?", a);
        jdbc.update("UPDATE knowledge_card SET version=3 WHERE id=?", b);
        var staleSource = post(b, 2, a, 2, "RELATED", "来源版本旧");
        conflict(staleSource, "CARD_VERSION_CHANGED", 3);
        assertThat(staleSource.getBody().path("error").path("message").asText()).contains("起点", b);
        var staleTarget = post(b, 3, a, 1, "RELATED", "目标版本旧");
        conflict(staleTarget, "CARD_VERSION_CHANGED", 2);
        assertThat(staleTarget.getBody().path("error").path("message").asText()).contains("终点", a);
        conflict(post(b, 1, a, 1, "RELATED", "双端版本旧"), "CARD_VERSION_CHANGED", 3);
        data(post(b, 3, a, 2, "RELATED", "核对正确"));
    }

    @Test void enforcesOwnershipActiveEndpointsAuthenticationAndNoSelfRelation() {
        String a = create(web, "Math"), b = create(other, "Math"), deleted = create(web, "Math");
        jdbc.update("UPDATE knowledge_card SET status='DELETED' WHERE id=?", deleted);
        for (String target : List.of(b, deleted, "9223372036854775807"))
            assertThat(post(a, 1, target, 1, "RELATED", "依据").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var self = post(a, 1, a, 1, "RELATED", "本人同卡");
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(self.getBody().path("error").path("code").asText()).isEqualTo("SELF_RELATION");
        for (String path : List.of("/card-relations", "/knowledge-graphs"))
            assertThat(get(path, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/card-relations", HttpMethod.POST, body(a, 1, b, 1, "RELATED", "依据"), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/card-relations/1", HttpMethod.DELETE, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(data(get("/card-relations", web)).path("total").asInt()).isZero();
    }

    @Test void concurrentReverseRelatedCreatesExactlyOneWithoutOverwritingEvidence() throws Exception {
        String a = create(web, "Math"), b = create(web, "Math");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return post(a, 1, b, 1, "RELATED", "第一份说明"); });
            var two = pool.submit(() -> { start.await(); return post(b, 1, a, 1, "RELATED", "第二份说明"); }); start.countDown();
            var x = one.get(10, TimeUnit.SECONDS); var y = two.get(10, TimeUnit.SECONDS);
            assertThat(List.of(x.getStatusCode().value(), y.getStatusCode().value())).containsExactlyInAnyOrder(201, 409);
            var winner = x.getStatusCode() == HttpStatus.CREATED ? x : y;
            var loser = x.getStatusCode() == HttpStatus.CONFLICT ? x : y;
            assertThat(loser.getBody().path("error").path("code").asText()).isEqualTo("RELATION_EXISTS");
            conflict(post(b, 1, a, 1, "RELATED", "绝不覆盖"), "RELATION_EXISTS", null);
            JsonNode page = data(get("/card-relations", web));
            assertThat(page.path("total").asInt()).isEqualTo(1);
            assertThat(page.path("items").get(0)).isEqualTo(winner.getBody().path("data"));
        }
    }

    @Test void paginatesOwnVisibleRelationsOnEitherEndpointAndValidatesCardFilter() {
        String a = create(web, "Math"), b = create(web, "Math"), c = create(web, "Math");
        List<String> ids = new ArrayList<>();
        for (String type : List.of("RELATED", "EXAMPLE", "PREREQUISITE")) ids.add(data(post(a, 1, b, 1, type, "依据")).path("id").asText());
        data(post(b, 1, c, 1, "RELATED", "隐藏已删端点"));
        jdbc.update("UPDATE knowledge_card SET status='DELETED' WHERE id=?", c);
        String foreign = create(other, "Math"), foreign2 = create(other, "Math");
        data(exchange("/card-relations", HttpMethod.POST, body(foreign, 1, foreign2, 1, "RELATED", "别人关系"), other));
        JsonNode page = data(get("/card-relations?cardId=" + b + "&page=2&pageSize=2", mini));
        assertThat(page.path("total").asInt()).isEqualTo(3);
        assertThat(page.path("page").asInt()).isEqualTo(2); assertThat(page.path("pageSize").asInt()).isEqualTo(2);
        assertThat(page.path("items")).hasSize(1); assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(ids.get(0));
        assertThat(data(get("/card-relations?cardId=" + a, web)).path("total").asInt()).isEqualTo(3);
        for (String invalid : List.of(foreign, c, "0", "bad", "9223372036854775808", ""))
            assertThat(get("/card-relations?cardId=" + invalid, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (String query : List.of("page=0", "pageSize=0", "pageSize=101"))
            assertThat(get("/card-relations?" + query, web).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(data(get("/card-relations?page=100&pageSize=100", web)).path("items")).isEmpty();
    }

    @Test void deletingIsIdempotentAndNeverDeletesOthersOrChangesCards() {
        String a = create(web, "Math"), b = create(web, "Math");
        String id = data(post(a, 1, b, 1, "RELATED", "删除说明")).path("id").asText();
        JsonNode beforeA = data(get("/cards/" + a, web)), beforeB = data(get("/cards/" + b, web));
        assertThat(exchange("/card-relations/" + id, HttpMethod.DELETE, null, other).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(data(get("/card-relations", web)).path("total").asInt()).isEqualTo(1);
        for (int i = 0; i < 2; i++) assertThat(exchange("/card-relations/" + id, HttpMethod.DELETE, null, mini).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(data(get("/card-relations", web)).path("total").asInt()).isZero();
        assertThat(data(get("/cards/" + a, web))).isEqualTo(beforeA); assertThat(data(get("/cards/" + b, web))).isEqualTo(beforeB);
    }

    @Test void graphIsBidirectionalOneHopAndSubjectAndOwnershipFilterAreAppliedTogether() {
        String a = create(web, "Math"), b = create(web, "Math"), c = create(web, "Math"), hop2 = create(web, "Math"),
            isolated = create(web, "Math"), science = create(web, "Science"), deleted = create(web, "Math"), foreign = create(other, "Math");
        data(post(b, 1, a, 1, "PREREQUISITE", "入边")); data(post(a, 1, c, 1, "EXAMPLE", "出边"));
        data(post(c, 1, hop2, 1, "RELATED", "二跳")); data(post(a, 1, science, 1, "RELATED", "其他学科"));
        data(post(a, 1, deleted, 1, "RELATED", "已删端点")); jdbc.update("UPDATE knowledge_card SET status='DELETED' WHERE id=?", deleted);
        insertRelation(userId, a, foreign, "RELATED");
        JsonNode graph = data(get("/knowledge-graphs?subject=math&rootCardId=" + a, mini));
        assertThat(nodeIds(graph)).containsExactly(a, b, c); assertThat(graph.path("edges")).hasSize(2); assertThat(graph.path("hasMore").asBoolean()).isFalse(); noDangling(graph);
        assertThat(nodeIds(data(get("/knowledge-graphs?subject=Math&rootCardId=" + isolated, web)))).containsExactly(isolated);
        assertThat(nodeIds(data(get("/knowledge-graphs?subject=Science&rootCardId=" + a, web)))).isEmpty();
        assertThat(nodeIds(data(get("/knowledge-graphs?subject=%20%20", web)))).contains(a, b, c, hop2, isolated, science).doesNotContain(deleted, foreign);
        for (String invalid : List.of(foreign, deleted, "bad")) assertThat(get("/knowledge-graphs?subject=NoMatch&rootCardId=" + invalid, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/knowledge-graphs?subject=" + "s".repeat(65), web).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test void graphNodeCapUsesPlusOneAndKeepsRootEvenWhenItsIdIsLast() {
        List<String> ids = insertCards(200, "Cap");
        JsonNode exact = data(get("/knowledge-graphs?subject=Cap", web));
        assertThat(exact.path("nodes")).hasSize(200); assertThat(exact.path("hasMore").asBoolean()).isFalse();
        String root = create(web, "Cap"); for (String id : ids) insertRelation(userId, id, root, "RELATED");
        JsonNode capped = data(get("/knowledge-graphs?subject=Cap", web));
        assertThat(capped.path("nodes")).hasSize(200); assertThat(capped.path("hasMore").asBoolean()).isTrue(); noDangling(capped);
        JsonNode rooted = data(get("/knowledge-graphs?subject=Cap&rootCardId=" + root, web));
        assertThat(rooted.path("nodes")).hasSize(200); assertThat(rooted.path("nodes").get(0).path("id").asText()).isEqualTo(root);
        assertThat(nodeIds(rooted).subList(1, 200)).containsExactlyElementsOf(ids.subList(0, 199));
        assertThat(rooted.path("edges")).hasSize(199); assertThat(rooted.path("hasMore").asBoolean()).isTrue(); noDangling(rooted);
    }

    @Test void graphEdgeCapUsesPlusOneAndOrdersEdgesAscending() {
        List<String> ids = insertCards(26, "Dense"); int count = 0;
        outer: for (String a : ids) for (String b : ids) if (!a.equals(b)) {
            insertRelation(userId, a, b, "EXAMPLE"); if (++count == 600) break outer;
        }
        JsonNode exact = data(get("/knowledge-graphs?subject=Dense", web));
        assertThat(exact.path("edges")).hasSize(600); assertThat(exact.path("hasMore").asBoolean()).isFalse(); noDangling(exact);
        insertRelation(userId, ids.get(0), ids.get(1), "PREREQUISITE");
        JsonNode capped = data(get("/knowledge-graphs?subject=Dense", web));
        assertThat(capped.path("edges")).hasSize(600); assertThat(capped.path("hasMore").asBoolean()).isTrue(); noDangling(capped);
        List<Long> edgeIds = new ArrayList<>(); capped.path("edges").forEach(edge -> edgeIds.add(edge.path("id").asLong())); assertThat(edgeIds).isSorted();
        assertThat(capped.path("edges")).isEqualTo(exact.path("edges"));
    }

    @Test void existingTaskConfirmationRelationsAreVisibleWithoutChangingItsTransaction() {
        String existing = create(web, "Math");
        String material = data(exchange("/materials", HttpMethod.POST, Map.of("type", "NOTE", "title", "历史确认", "noteText", "真实原文。".repeat(180)), web)).path("id").asText();
        String task = data(exchange("/ai-tasks", HttpMethod.POST, Map.of("materialId", material, "materialVersion", 1, "requestKey", "d19", "goalType", "REVIEW_CARDS", "externalProcessingConsent", true), web)).path("id").asText();
        var lease = leases.claim(); assertThat(lease.taskId()).isEqualTo(Long.parseLong(task)); processor.execute(lease);
        JsonNode candidate = data(get("/ai-tasks/" + task + "/result", web));
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("resultVersion", candidate.path("resultVersion").asInt()).put("sourceVersion", 1).put("summary", "人工核对").put("tagMode", "MERGE");
        input.putArray("tags"); input.putArray("cards").add(candidate.path("cards").get(0).deepCopy());
        ObjectNode relation = input.putArray("relations").addObject(); relation.put("relationKey", "r1").put("type", "RELATED").put("evidenceText", "历史确认依据");
        relation.putObject("source").put("candidateKey", candidate.path("cards").get(0).path("candidateKey").asText());
        relation.putObject("target").put("cardId", existing).put("cardVersion", 1);
        data(exchange("/ai-tasks/" + task + "/confirmation", HttpMethod.PUT, input, web));
        JsonNode page = data(get("/card-relations?cardId=" + existing, mini));
        assertThat(page.path("total").asInt()).isEqualTo(1); assertThat(page.path("items").get(0).path("evidenceText").asText()).isEqualTo("历史确认依据");
        assertThat(data(get("/knowledge-graphs?rootCardId=" + existing, web)).path("edges").get(0)).isEqualTo(page.path("items").get(0));
    }

    @Test void unavailableHistoricalSourceDoesNotBlockManualRelationAndRemainsHidden() {
        String a = create(web, "Math"), b = create(web, "Math");
        String material = data(exchange("/materials", HttpMethod.POST, Map.of("type", "NOTE", "title", "失效来源", "noteText", "真实原文。"), web)).path("id").asText();
        jdbc.update("INSERT INTO material_chunk(material_id,material_version,chunk_no,content,extraction_method,created_at) VALUES(?,1,1,'真实原文。','TEXT',UTC_TIMESTAMP(6))", material);
        Long chunk = jdbc.queryForObject("SELECT id FROM material_chunk WHERE material_id=?", Long.class, material);
        jdbc.update("INSERT INTO card_source(card_id,chunk_id,quote_text,start_offset,end_offset,created_at) VALUES(?,?,'真实原文',0,4,UTC_TIMESTAMP(6))", a, chunk);
        jdbc.update("UPDATE material SET access_state='RESTRICTED' WHERE id=?", material);
        data(post(a, 1, b, 1, "RELATED", "历史来源受限后人工确认"));
        for (String status : List.of("ACTIVE", "DELETED")) {
            jdbc.update("UPDATE material SET status=? WHERE id=?", status, material);
            JsonNode source = data(get("/cards/" + a + "/sources", web)).path("items").get(0);
            assertThat(source.path("isAvailable").asBoolean()).isFalse(); assertThat(source.path("content").isNull()).isTrue(); assertThat(source.path("quoteText").isNull()).isTrue();
        }
        data(post(b, 1, a, 1, "EXAMPLE", "原文已删仍可确认关系"));
        assertThat(data(get("/card-relations", web)).path("total").asInt()).isEqualTo(2);
    }

    @Test void databaseFailuresAreNotReportedAsSuccessOrEmptyGraph() {
        String a = create(web, "Math"), b = create(web, "Math"), trigger = "d19_delete_" + userId;
        String relation = data(post(a, 1, b, 1, "RELATED", "保留")).path("id").asText();
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE DELETE ON card_relation FOR EACH ROW BEGIN IF OLD.user_id=" + userId + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='induced delete failure'; END IF; END");
        try { assertThat(exchange("/card-relations/" + relation, HttpMethod.DELETE, null, web).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR); }
        finally { jdbc.execute("DROP TRIGGER " + trigger); }
        assertThat(data(get("/card-relations", web)).path("total").asInt()).isEqualTo(1);
        jdbc.update("UPDATE knowledge_card SET tags_json=JSON_OBJECT() WHERE id=?", a);
        assertThat(get("/knowledge-graphs", web).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private List<String> insertCards(int count, String subject) {
        for (int i = 0; i < count; i++) jdbc.update("INSERT INTO knowledge_card(user_id,title,content,subject,tags_json,version,origin,generation_mode,is_edited,status,created_at,updated_at) VALUES(?,'节点','正文',?,JSON_ARRAY(),1,'MANUAL','MANUAL',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", userId, subject);
        return jdbc.queryForList("SELECT CAST(id AS CHAR) FROM knowledge_card WHERE user_id=? AND subject=? ORDER BY id", String.class, userId, subject);
    }
    private void insertRelation(long owner, String a, String b, String type) { jdbc.update("INSERT INTO card_relation(user_id,source_card_id,target_card_id,relation_type,evidence_text,origin,created_at) VALUES(?,?,?,?,'人工依据','USER_CONFIRMED',UTC_TIMESTAMP(6))", owner, a, b, type); }
    private List<String> nodeIds(JsonNode graph) { List<String> ids = new ArrayList<>(); graph.path("nodes").forEach(node -> ids.add(node.path("id").asText())); return ids; }
    private void noDangling(JsonNode graph) { Set<String> ids = new HashSet<>(nodeIds(graph)); graph.path("edges").forEach(edge -> { assertThat(ids).contains(edge.path("sourceCardId").asText(), edge.path("targetCardId").asText()); }); }
    private Map<String, Object> body(String a, int av, String b, int bv, String type, String evidence) { return Map.of("sourceCardId", a, "sourceCardVersion", av, "targetCardId", b, "targetCardVersion", bv, "type", type, "evidenceText", evidence); }
    private ResponseEntity<JsonNode> post(String a, int av, String b, int bv, String type, String evidence) { return exchange("/card-relations", HttpMethod.POST, body(a, av, b, bv, type, evidence), web); }
    private String create(String token, String subject) { return data(exchange("/cards", HttpMethod.POST, Map.of("title", "卡片", "content", "正文", "subject", subject, "tags", List.of("重点")), token)).path("id").asText(); }
    private JsonNode data(ResponseEntity<JsonNode> response) { assertThat(response.getStatusCode()).as("response: %s", response.getBody()).isIn(HttpStatus.OK, HttpStatus.CREATED, HttpStatus.ACCEPTED); return response.getBody().path("data"); }
    private void conflict(ResponseEntity<JsonNode> response, String code, Integer version) { assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); assertThat(response.getBody().path("error").path("code").asText()).isEqualTo(code); if (version != null) assertThat(response.getBody().path("error").path("currentVersion").asInt()).isEqualTo(version); }
    private long register(String username, String password) { var response = rest.postForEntity(url("/auth/registrations"), Map.of("username", username, "password", password, "displayName", username), JsonNode.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); return response.getBody().path("data").path("id").asLong(); }
    private String login(String username, String password, String type) { var response = rest.postForEntity(url("/auth/sessions"), Map.of("username", username, "password", password, "clientType", type), JsonNode.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); return response.getBody().path("data").path("accessToken").asText(); }
    private ResponseEntity<JsonNode> get(String path, String token) { return exchange(path, HttpMethod.GET, null, token); }
    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) { HttpHeaders headers = new HttpHeaders(); if (token != null) headers.setBearerAuth(token); if (body instanceof String) headers.setContentType(MediaType.APPLICATION_JSON); return rest.exchange(java.net.URI.create(url(path)), method, new HttpEntity<>(body, headers), JsonNode.class); }
    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
