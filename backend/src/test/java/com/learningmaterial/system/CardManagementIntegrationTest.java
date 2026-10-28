package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class CardManagementIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    private long userId, otherId;
    private String web, mini, other;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Safe_" + suffix;
        userId = register("card_" + suffix, password);
        otherId = register("cardother_" + suffix, password);
        web = login("card_" + suffix, password, "WEB");
        mini = login("card_" + suffix, password, "MINIPROGRAM");
        other = login("cardother_" + suffix, password, "WEB");
    }

    @AfterEach
    void clean() {
        for (long id : List.of(userId, otherId)) {
            jdbc.update("DELETE FROM favorite WHERE user_id=?", id);
            jdbc.update("DELETE FROM card_relation WHERE user_id=?", id);
            jdbc.update("DELETE FROM card_source WHERE card_id IN (SELECT id FROM knowledge_card WHERE user_id=?)", id);
            jdbc.update("UPDATE knowledge_card SET parent_card_id=NULL WHERE user_id=?", id);
            jdbc.update("DELETE FROM knowledge_card WHERE user_id=?", id);
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
    void manualCreationHasHonestMetadataAndNoAutomaticSources() {
        assertThat(exchange("/cards", HttpMethod.POST, Map.of("title", "标题", "content", "正文"), null)
            .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode card = create(web, Map.of("title", "  手工卡片  ", "content", " 第一行\n第二行 ",
            "summary", "摘要", "subject", " 数学 ", "tags", List.of(" 重点 ", "复习")));
        String id = card.path("id").asText();
        assertThat(card.path("title").asText()).isEqualTo("手工卡片");
        assertThat(card.path("content").asText()).isEqualTo(" 第一行\n第二行 ");
        assertThat(card.path("subject").asText()).isEqualTo("数学");
        assertThat(card.path("tags").get(0).asText()).isEqualTo("重点");
        assertThat(card.path("version").asInt()).isEqualTo(1);
        assertThat(card.path("origin").asText()).isEqualTo("MANUAL");
        assertThat(card.path("generationMode").asText()).isEqualTo("MANUAL");
        assertThat(card.path("isEdited").asBoolean()).isFalse();
        assertThat(card.path("favorite").isBoolean()).isTrue();
        assertThat(card.path("favorite").asBoolean()).isFalse();
        assertThat(card.path("parentCardId").isNull()).isTrue();
        assertThat(get("/cards/" + id + "/sources", mini).path("data").path("items").size()).isZero();
        assertThat(jdbc.queryForMap("SELECT task_id,candidate_key FROM knowledge_card WHERE id=?", id))
            .containsEntry("task_id", null).containsEntry("candidate_key", null);
        assertThat(exchange("/cards/" + id, HttpMethod.GET, null, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/cards/" + id + "/sources", HttpMethod.GET, null, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/cards/" + id, HttpMethod.PATCH, Map.of("version", 1, "title", "越权"), other)
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void creationEnforcesFieldBoundariesAndRejectsServerOwnedFields() {
        JsonNode max = create(web, Map.of("title", "题".repeat(255), "content", "文".repeat(8000),
            "summary", "摘".repeat(2000), "subject", "科".repeat(64),
            "tags", java.util.stream.IntStream.range(0, 10).mapToObj(i -> i + "签".repeat(63)).toList()));
        assertThat(max.path("tags").size()).isEqualTo(10);
        Map<String, Object> defaults = new HashMap<>(Map.of("title", "标题", "content", "正文"));
        defaults.put("tags", null);
        assertThat(create(web, defaults).path("tags").size()).isZero();
        for (Map<String, Object> change : List.<Map<String, Object>>of(
            Map.of("title", " "), Map.of("title", "题".repeat(256)),
            Map.of("content", " \n "), Map.of("content", "文".repeat(8001)),
            Map.of("summary", "摘".repeat(2001)), Map.of("subject", "科".repeat(65)),
            Map.of("tags", List.of("重点", " 重点 ")), Map.of("tags", List.of(" ")),
            Map.of("tags", List.of("签".repeat(65))), Map.of("tags", Collections.nCopies(11, "签")))) {
            Map<String, Object> body = new HashMap<>(Map.of("title", "标题", "content", "正文"));
            body.putAll(change);
            assertThat(exchange("/cards", HttpMethod.POST, body, web).getStatusCode())
                .as("invalid field %s", change.keySet()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        for (String field : List.of("origin", "generationMode", "version", "userId", "taskId", "parentCardId", "sources")) {
            Map<String, Object> body = new HashMap<>(Map.of("title", "标题", "content", "正文"));
            body.put(field, "forbidden");
            assertThat(exchange("/cards", HttpMethod.POST, body, web).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void creationRejectsCaseInsensitiveDuplicateTagsAndPreservesEnteredSpelling() {
        assertThat(exchange("/cards", HttpMethod.POST,
            Map.of("title", "重复标签", "content", "正文", "tags", List.of(" Tag ", "tag")), web)
            .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode card = create(web, Map.of("title", "标签", "content", "正文", "tags", List.of(" Tag ", "Other")));
        assertThat(card.path("tags").get(0).asText()).isEqualTo("Tag");
        assertThat(card.path("tags").get(1).asText()).isEqualTo("Other");
    }

    @Test
    void patchRejectsCaseInsensitiveDuplicateTagsWithoutChangingStoredCard() {
        String id = create(web, Map.of("title", "原稿", "content", "正文", "tags", List.of("Original")))
            .path("id").asText();
        assertThat(exchange("/cards/" + id, HttpMethod.PATCH,
            Map.of("version", 1, "tags", List.of(" Tag ", "tag")), mini).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode unchanged = get("/cards/" + id, web).path("data");
        assertThat(unchanged.path("version").asInt()).isEqualTo(1);
        assertThat(unchanged.path("tags").get(0).asText()).isEqualTo("Original");
        JsonNode updated = exchange("/cards/" + id, HttpMethod.PATCH,
            Map.of("version", 1, "tags", List.of(" NewTag ", "Other")), mini).getBody().path("data");
        assertThat(updated.path("tags").get(0).asText()).isEqualTo("NewTag");
        assertThat(updated.path("tags").get(1).asText()).isEqualTo("Other");
    }

    @Test
    void partialEditPreservesOmittedFieldsAndExplicitlyClearsOptionalFields() {
        String id = create(web, Map.of("title", "原题", "content", "原文\n保留", "summary", "摘要",
            "subject", "数学", "tags", List.of("原签"))).path("id").asText();
        Map<String, Object> patch = new HashMap<>(Map.of("version", 1, "title", " 新题 ", "subject", " "));
        patch.put("summary", null);
        ResponseEntity<JsonNode> updated = exchange("/cards/" + id, HttpMethod.PATCH, patch, mini);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode card = updated.getBody().path("data");
        assertThat(card.path("title").asText()).isEqualTo("新题");
        assertThat(card.path("summary").isNull()).isTrue();
        assertThat(card.path("subject").isNull()).isTrue();
        assertThat(card.path("content").asText()).isEqualTo("原文\n保留");
        assertThat(card.path("tags").get(0).asText()).isEqualTo("原签");
        assertThat(card.path("version").asInt()).isEqualTo(2);
        assertThat(card.path("isEdited").asBoolean()).isTrue();
        assertThat(card.path("origin").asText()).isEqualTo("MANUAL");
        for (String field : List.of("title", "content", "tags")) {
            Map<String, Object> invalid = new HashMap<>(Map.of("version", 2));
            invalid.put(field, null);
            assertThat(exchange("/cards/" + id, HttpMethod.PATCH, invalid, web).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        for (Object invalid : List.of(Map.of("version", 2), Map.of("title", "无版本"),
            Map.of("version", 0, "title", "版本无效"), Map.of("version", 2, "content", " "),
            Map.of("version", 2, "tags", List.of("签", " 签 ")),
            Map.of("version", 2, "summary", "摘".repeat(2001)))) {
            assertThat(exchange("/cards/" + id, HttpMethod.PATCH, invalid, web).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        assertThat(exchange("/cards/" + id, HttpMethod.PATCH, Map.of("version", 2, "origin", "AI"), web)
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/cards/" + id, HttpMethod.PATCH,
            Map.of("version", 2, "content", " 新正文\n ", "tags", List.of()), web).getBody()
            .path("data").path("content").asText()).isEqualTo(" 新正文\n ");
    }

    @Test
    void concurrentWebAndMiniEditsProduceOneWinnerAndAnExplicitCurrentVersion() throws Exception {
        String id = create(web, Map.of("title", "原稿", "content", "原文")).path("id").asText();
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<JsonNode>> first = editAfter(start, id, web, "网页草稿");
        CompletableFuture<ResponseEntity<JsonNode>> second = editAfter(start, id, mini, "小程序草稿");
        start.countDown();
        ResponseEntity<JsonNode> a = first.get(10, TimeUnit.SECONDS), b = second.get(10, TimeUnit.SECONDS);
        assertThat(List.of(a.getStatusCode(), b.getStatusCode())).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        JsonNode conflict = (a.getStatusCode() == HttpStatus.CONFLICT ? a : b).getBody().path("error");
        assertThat(conflict.path("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(conflict.path("currentVersion").asInt()).isEqualTo(2);
        JsonNode winner = get("/cards/" + id, web).path("data");
        assertThat(winner.path("version").asInt()).isEqualTo(2);
        assertThat(winner.path("title").asText()).isIn("网页草稿", "小程序草稿");
    }

    @Test
    void searchCombinesOwnedFiltersAndTreatsSqlWildcardsAndQuotesLiterally() {
        String a = create(web, Map.of("title", "百分%_号'卡片", "content", "矩阵正文", "subject", "数学",
            "tags", List.of("重点"))).path("id").asText();
        String b = create(web, Map.of("title", "其他卡片", "content", "矩阵正文", "subject", "数学",
            "tags", List.of("普通"))).path("id").asText();
        create(web, Map.of("title", "历史", "content", "矩阵材料", "subject", "历史", "tags", List.of("重点")));
        create(other, Map.of("title", "百分%_号'他人", "content", "矩阵正文", "subject", "数学", "tags", List.of("重点")));
        assertThat(total("/cards?keyword=" + query("%_'"), web)).isEqualTo(0);
        assertThat(total("/cards?keyword=" + query("%_号'"), web)).isEqualTo(1);
        assertThat(total("/cards?keyword=" + query("' OR 1=1 --"), web)).isZero();
        assertThat(total("/cards?keyword=矩阵&subject=数学&tag=重点", web)).isEqualTo(1);
        assertThat(total("/cards?keyword=矩阵&subject=数学&tag=重点&favoriteOnly=true", web)).isZero();
        exchange("/favorites/CARD/" + a, HttpMethod.PUT, null, mini);
        assertThat(total("/cards?keyword=矩阵&subject=数学&tag=重点&favoriteOnly=true", web)).isEqualTo(1);
        assertThat(total("/cards?subject=数&tag=重点", web)).isZero();
        assertThat(total("/cards?tag=重", web)).isZero();
        assertThat(total("/cards?keyword=" + query(" ") + "&subject=" + query(" ") + "&tag=" + query(" "), web)).isEqualTo(3);
        jdbc.update("UPDATE knowledge_card SET updated_at='2026-01-01 00:00:00' WHERE user_id=?", userId);
        JsonNode p1 = get("/cards?pageSize=1", web).path("data"), p2 = get("/cards?page=2&pageSize=1", web).path("data");
        assertThat(p1.path("total").asInt()).isEqualTo(3);
        assertThat(p1.path("items").get(0).path("id").asLong()).isGreaterThan(p2.path("items").get(0).path("id").asLong());
        jdbc.update("UPDATE knowledge_card SET status='DELETED' WHERE id=?", b);
        assertThat(total("/cards", web)).isEqualTo(2);
        assertThat(exchange("/cards/" + b, HttpMethod.GET, null, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (String invalid : List.of("page=0", "pageSize=0", "pageSize=101", "keyword=" + "a".repeat(101),
            "subject=" + "a".repeat(65), "tag=" + "a".repeat(65))) {
            assertThat(exchange("/cards?" + invalid, HttpMethod.GET, null, web).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    void cardFavoritesAreIdempotentPrivateAndMixedPagesHaveOneGlobalOrder() {
        String card = create(web, Map.of("title", "收藏卡", "content", "正文")).path("id").asText();
        String foreign = create(other, Map.of("title", "他人卡", "content", "正文")).path("id").asText();
        String note = exchange("/materials", HttpMethod.POST, Map.of("type", "NOTE", "title", "收藏资料", "noteText", "正文"), web)
            .getBody().path("data").path("id").asText();
        assertThat(exchange("/favorites/CARD/" + foreign, HttpMethod.PUT, null, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/favorites/UNKNOWN/" + card, HttpMethod.PUT, null, web).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/favorites/CARD/" + card, HttpMethod.PUT, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/favorites/CARD/" + card, HttpMethod.PUT, null, web).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/favorites/CARD/" + card, HttpMethod.PUT, null, mini).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/cards/" + card, web).path("data").path("favorite").asBoolean()).isTrue();
        assertThat(get("/cards/" + card, web).path("data").path("version").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM favorite WHERE user_id=? AND card_id=?", Long.class, userId, card)).isEqualTo(1);
        exchange("/favorites/MATERIAL/" + note, HttpMethod.PUT, null, web);
        jdbc.update("UPDATE favorite SET created_at='2026-01-01 00:00:00' WHERE user_id=?", userId);
        JsonNode page1 = get("/favorites?pageSize=1", web).path("data"), page2 = get("/favorites?page=2&pageSize=1", web).path("data");
        assertThat(page1.path("total").asInt()).isEqualTo(2);
        assertThat(page1.path("items").get(0).path("type").asText()).isEqualTo("MATERIAL");
        assertThat(page2.path("items").get(0).path("type").asText()).isEqualTo("CARD");
        assertThat(get("/favorites?type=CARD", web).path("data").path("items").get(0).path("targetId").asText()).isEqualTo(card);
        assertThat(total("/favorites?type=MATERIAL", web)).isEqualTo(1);
        assertThat(total("/favorites", other)).isZero();
        assertThat(exchange("/favorites?type=UNKNOWN", HttpMethod.GET, null, web).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/favorites/CARD/" + card, HttpMethod.DELETE, null, other).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(total("/cards?favoriteOnly=true", web)).isEqualTo(1);
        for (int i = 0; i < 2; i++) assertThat(exchange("/favorites/CARD/" + card, HttpMethod.DELETE, null, mini)
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/cards/" + card, web).path("data").path("favorite").asBoolean()).isFalse();
        assertThat(get("/cards/" + card, web).path("data").path("version").asInt()).isEqualTo(1);
        exchange("/favorites/CARD/" + card, HttpMethod.PUT, null, web);
        jdbc.update("UPDATE knowledge_card SET status='DELETED' WHERE id=?", card);
        assertThat(total("/favorites?type=CARD", web)).isZero();
        assertThat(total("/favorites", web)).isEqualTo(1);
        assertThat(exchange("/favorites/CARD/" + card, HttpMethod.PUT, null, web).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void editingAiCardPreservesItsGenerationMetadataAndHistoricalSources() {
        String material = exchange("/materials", HttpMethod.POST, Map.of("type", "NOTE", "title", "来源", "noteText", "历史原文"), web)
            .getBody().path("data").path("id").asText();
        jdbc.update("""
            INSERT INTO ai_task(user_id,material_id,material_version,request_key,request_hash,goal_type,status,
              step_count,cancel_flag,execution_epoch,config_json,created_at,updated_at)
            VALUES(?,?,1,'d17-fixture',REPEAT('a',64),'REVIEW_CARDS','SUCCEEDED',0,0,1,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """, userId, material);
        long task = jdbc.queryForObject("SELECT MAX(id) FROM ai_task WHERE user_id=?", Long.class, userId);
        jdbc.update("""
            INSERT INTO material_chunk(material_id,material_version,chunk_no,content,extraction_method,created_at)
            VALUES(?,1,1,'历史原文','TEXT',UTC_TIMESTAMP(6))
            """, material);
        long chunk = jdbc.queryForObject("SELECT MAX(id) FROM material_chunk WHERE material_id=?", Long.class, material);
        for (String mode : List.of("MOCK", "MODEL")) {
            jdbc.update("""
                INSERT INTO knowledge_card(user_id,task_id,candidate_key,title,content,tags_json,version,origin,generation_mode,is_edited,status,created_at,updated_at)
                VALUES(?,?,?,'AI原题','历史原文',JSON_ARRAY(),1,'AI',?,0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, userId, task, "d17-" + mode, mode);
            long card = jdbc.queryForObject("SELECT MAX(id) FROM knowledge_card WHERE user_id=?", Long.class, userId);
            jdbc.update("INSERT INTO card_source(card_id,chunk_id,quote_text,start_offset,end_offset,created_at) VALUES(?,?,'历史原文',0,4,UTC_TIMESTAMP(6))", card, chunk);
            JsonNode before = get("/cards/" + card + "/sources", web).path("data");
            ResponseEntity<JsonNode> updated = exchange("/cards/" + card, HttpMethod.PATCH, Map.of("version", 1, "content", "人工改写内容"), mini);
            assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode result = updated.getBody().path("data");
            assertThat(result.path("origin").asText()).isEqualTo("AI");
            assertThat(result.path("generationMode").asText()).isEqualTo(mode);
            assertThat(result.path("isEdited").asBoolean()).isTrue();
            assertThat(get("/cards/" + card + "/sources", web).path("data")).isEqualTo(before);
        }
        jdbc.update("UPDATE material SET status='DELETED' WHERE id=?", material);
        long card = jdbc.queryForObject("SELECT MAX(id) FROM knowledge_card WHERE user_id=?", Long.class, userId);
        JsonNode unavailable = get("/cards/" + card + "/sources", web).path("data").path("items").get(0);
        assertThat(unavailable.path("isAvailable").asBoolean()).isFalse();
        assertThat(unavailable.path("unavailableReason").asText()).isEqualTo("SOURCE_DELETED");
        assertThat(unavailable.path("content").isNull()).isTrue();
    }

    @Test
    void databaseEnforcesManualProvenanceAndRealCardFavoriteForeignKey() {
        String id = create(web, Map.of("title", "数据库约束", "content", "正文")).path("id").asText();
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_card SET generation_mode='MODEL' WHERE id=?", id))
            .isInstanceOf(DataAccessException.class).rootCause().isInstanceOfSatisfying(SQLException.class, failure -> {
                assertThat(failure.getErrorCode()).isEqualTo(3819);
                assertThat(failure.getMessage()).contains("ck_card_provenance");
            });
        assertThatThrownBy(() -> jdbc.update("INSERT INTO favorite(user_id,card_id,created_at) VALUES(?,9223372036854775807,UTC_TIMESTAMP(6))", userId))
            .isInstanceOf(DataAccessException.class).rootCause().isInstanceOfSatisfying(SQLException.class, failure -> {
                assertThat(failure.getErrorCode()).isEqualTo(1452);
                assertThat(failure.getMessage()).contains("fk_favorite_card");
            });
    }

    private CompletableFuture<ResponseEntity<JsonNode>> editAfter(CountDownLatch start, String id, String token, String title) {
        return CompletableFuture.supplyAsync(() -> {
            try { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            return exchange("/cards/" + id, HttpMethod.PATCH, Map.of("version", 1, "title", title), token);
        });
    }

    private JsonNode create(String token, Object body) {
        ResponseEntity<JsonNode> response = exchange("/cards", HttpMethod.POST, body, token);
        assertThat(response.getStatusCode()).as("card creation: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("data");
    }

    private long register(String username, String password) {
        ResponseEntity<JsonNode> response = rest.postForEntity(url("/auth/registrations"),
            Map.of("username", username, "password", password, "displayName", username), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("data").path("id").asLong();
    }

    private String login(String username, String password, String clientType) {
        ResponseEntity<JsonNode> response = rest.postForEntity(url("/auth/sessions"),
            Map.of("username", username, "password", password, "clientType", clientType), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("data").path("accessToken").asText();
    }

    private JsonNode get(String path, String token) { return exchange(path, HttpMethod.GET, null, token).getBody(); }
    private long total(String path, String token) { return get(path, token).path("data").path("total").asLong(); }
    private String query(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(java.net.URI.create(url(path)), method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
