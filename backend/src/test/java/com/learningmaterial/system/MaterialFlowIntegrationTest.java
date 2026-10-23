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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class MaterialFlowIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void miniProgramNoteIsVisibleOnWebAndPrivateToItsOwner() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String userA = "noter_" + suffix;
        String userB = "other_" + suffix;
        String password = "Safe_" + suffix;
        Long aId = null;
        Long bId = null;
        try {
            assertThat(get("/materials", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            aId = register(userA, password);
            bId = register(userB, password);
            String miniToken = login(userA, password, "MINIPROGRAM");
            String webToken = login(userA, password, "WEB");
            String otherToken = login(userB, password, "WEB");

            ResponseEntity<JsonNode> invalid = post("/materials", Map.of(
                "type", "NOTE", "title", "  ", "noteText", "正文"), miniToken);
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(post("/materials", Map.of("type", "NOTE", "title", "测试",
                "noteText", "正文", "tags", List.of("重点", "重点")), miniToken)
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(post("/materials", Map.of("type", "NOTE", "title", "超长正文",
                "noteText", "字".repeat(20001)), miniToken)
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            ResponseEntity<JsonNode> created = post("/materials", Map.of(
                "type", "NOTE", "title", "  线性代数第三章  ", "subject", "数学",
                "noteText", "特征值与特征向量\n课后复习", "tags", List.of("重点", "期末")), miniToken);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode material = created.getBody().path("data");
            String materialId = material.path("id").asText();
            assertThat(materialId).matches("[1-9][0-9]*");
            assertThat(material.path("title").asText()).isEqualTo("线性代数第三章");
            assertThat(material.path("version").asInt()).isEqualTo(1);
            assertThat(material.path("tags").size()).isEqualTo(2);

            ResponseEntity<JsonNode> webDetail = get("/materials/" + materialId, webToken);
            assertThat(webDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(webDetail.getBody().path("data").path("noteText").asText()).contains("特征值");
            assertThat(get("/materials?keyword=线性代数", webToken).getBody()
                .path("data").path("total").asLong()).isEqualTo(1);
            String tagId = get("/tags?keyword=重点", webToken).getBody()
                .path("data").path("items").get(0).path("id").asText();
            assertThat(get("/materials?tagId=" + tagId, webToken).getBody()
                .path("data").path("items").get(0).path("id").asText()).isEqualTo(materialId);
            assertThat(get("/materials?keyword=不存在", webToken).getBody()
                .path("data").path("total").asLong()).isZero();

            assertThat(get("/materials/" + materialId, otherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exchange("/materials/" + materialId, HttpMethod.PATCH,
                Map.of("version", 1, "title", "越权修改"), otherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exchange("/materials/" + materialId + "?version=1", HttpMethod.DELETE, null, otherToken)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get("/materials?tagId=" + tagId, otherToken).getBody()
                .path("data").path("total").asLong()).isZero();
            assertThat(get("/tags", otherToken).getBody().path("data").path("total").asLong()).isZero();
            assertThat(get("/materials?tagId=wrong", webToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material WHERE user_id = ?",
                Long.class, aId)).isEqualTo(1L);
        } finally {
            if (aId != null) {
                jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON mt.material_id = m.id WHERE m.user_id = ?", aId);
                jdbc.update("DELETE FROM material WHERE user_id = ?", aId);
                jdbc.update("DELETE FROM tag WHERE user_id = ?", aId);
                jdbc.update("DELETE FROM auth_session WHERE user_id = ?", aId);
                jdbc.update("DELETE FROM app_user WHERE id = ?", aId);
            }
            if (bId != null) {
                jdbc.update("DELETE FROM auth_session WHERE user_id = ?", bId);
                jdbc.update("DELETE FROM app_user WHERE id = ?", bId);
            }
        }
    }

    @Test
    void concurrentEditsKeepOneWinnerAndSoftDeleteHidesContent() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "editor_" + suffix;
        String password = "Safe_" + suffix;
        long userId = register(username, password);
        try {
            String firstToken = login(username, password, "WEB");
            String secondToken = login(username, password, "MINIPROGRAM");
            String id = post("/materials", Map.of("type", "NOTE", "title", "原稿", "noteText", "原正文"), firstToken)
                .getBody().path("data").path("id").asText();
            assertThat(get("/materials/" + id, firstToken).getBody().path("data").path("version").asInt()).isEqualTo(1);
            assertThat(get("/materials/" + id, secondToken).getBody().path("data").path("version").asInt()).isEqualTo(1);

            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<ResponseEntity<JsonNode>> first = CompletableFuture.supplyAsync(() -> {
                try { start.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                return exchange("/materials/" + id, HttpMethod.PATCH,
                    Map.of("version", 1, "title", "网页修改", "noteText", "网页正文", "tags", List.of("网页")), firstToken);
            });
            CompletableFuture<ResponseEntity<JsonNode>> second = CompletableFuture.supplyAsync(() -> {
                try { start.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                return exchange("/materials/" + id, HttpMethod.PATCH,
                    Map.of("version", 1, "title", "小程序修改", "noteText", "小程序正文", "tags", List.of("小程序")), secondToken);
            });
            start.countDown();
            ResponseEntity<JsonNode> a = first.get(10, TimeUnit.SECONDS);
            ResponseEntity<JsonNode> b = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(a.getStatusCode(), b.getStatusCode()))
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            ResponseEntity<JsonNode> stale = a.getStatusCode() == HttpStatus.CONFLICT ? a : b;
            assertThat(stale.getBody().path("error").path("currentVersion").asInt()).isEqualTo(2);
            JsonNode winner = get("/materials/" + id, firstToken).getBody().path("data");
            assertThat(winner.path("version").asInt()).isEqualTo(2);
            assertThat(winner.path("tags").size()).isEqualTo(1);
            assertThat(winner.path("title").asText()).isIn("网页修改", "小程序修改");
            assertThat(winner.path("noteText").asText()).isIn("网页正文", "小程序正文");

            assertThat(exchange("/materials/" + id, HttpMethod.PATCH,
                Map.of("version", 1, "status", "ARCHIVED"), secondToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exchange("/materials/" + id, HttpMethod.PATCH,
                Map.of("version", 2, "status", "ARCHIVED"), secondToken).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get("/materials", firstToken).getBody().path("data").path("total").asInt()).isZero();
            assertThat(get("/materials?status=ARCHIVED", firstToken).getBody().path("data").path("total").asInt()).isEqualTo(1);

            assertThat(exchange("/materials/" + id + "?version=2", HttpMethod.DELETE, null, firstToken)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exchange("/materials/" + id + "?version=3", HttpMethod.DELETE, null, firstToken)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(get("/materials/" + id, firstToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get("/materials?status=ALL", secondToken).getBody().path("data").path("total").asInt()).isZero();
            assertThat(exchange("/materials/" + id + "?version=4", HttpMethod.DELETE, null, firstToken)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(jdbc.queryForObject("SELECT status FROM material WHERE id = ?", String.class, Long.parseLong(id)))
                .isEqualTo("DELETED");
        } finally {
            jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON mt.material_id = m.id WHERE m.user_id = ?", userId);
            jdbc.update("DELETE FROM material WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM tag WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
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

    private ResponseEntity<JsonNode> post(String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.postForEntity(url(path), new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
