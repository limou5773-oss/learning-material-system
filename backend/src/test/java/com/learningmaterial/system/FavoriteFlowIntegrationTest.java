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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class FavoriteFlowIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void favoriteIsIdempotentPrivateAndRespectsSearchArchiveDeleteAndPaging() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Safe_" + suffix;
        long ownerId = register("fav_" + suffix, password);
        long otherId = register("favother_" + suffix, password);
        try {
            String owner = login("fav_" + suffix, password, "WEB");
            String mini = login("fav_" + suffix, password, "MINIPROGRAM");
            String other = login("favother_" + suffix, password, "WEB");
            String a = note(owner, "代数甲", "矩阵计算", "数学", "重点");
            String b = note(owner, "代数乙", "矩阵证明", "数学", "重点");
            String c = note(owner, "历史丙", "年代材料", "历史", "阅读");
            String foreign = note(other, "他人资料", "不可见", "数学", "重点");

            assertThat(exchange("/favorites/MATERIAL/" + a, HttpMethod.PUT, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange("/favorites/MATERIAL/" + foreign, HttpMethod.PUT, null, owner).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exchange("/favorites/CARD/1", HttpMethod.PUT, null, owner).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(exchange("/favorites/MATERIAL/nope", HttpMethod.PUT, null, owner).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

            assertThat(exchange("/favorites/MATERIAL/" + a, HttpMethod.PUT, null, owner).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(exchange("/favorites/MATERIAL/" + a, HttpMethod.PUT, null, mini).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM favorite WHERE user_id = ? AND material_id = ?",
                Long.class, ownerId, Long.parseLong(a))).isEqualTo(1L);
            assertThat(get("/materials/" + a, mini).getBody().path("data").path("favorite").asBoolean()).isTrue();
            assertThat(get("/materials/" + a, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get("/favorites", other).getBody().path("data").path("total").asLong()).isZero();

            assertThat(exchange("/favorites/MATERIAL/" + b, HttpMethod.PUT, null, owner).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(exchange("/materials/" + b, HttpMethod.PATCH,
                Map.of("version", 1, "status", "ARCHIVED"), owner).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get("/materials?favorite=true&status=ACTIVE", owner).getBody()
                .path("data").path("total").asLong()).isEqualTo(1);
            assertThat(get("/materials?favorite=true&status=ARCHIVED", owner).getBody()
                .path("data").path("items").get(0).path("id").asText()).isEqualTo(b);
            assertThat(get("/materials?favorite=true&status=ALL&keyword=矩阵&subject=数学", owner).getBody()
                .path("data").path("total").asLong()).isEqualTo(2);
            assertThat(get("/materials?keyword=年代材料&subject=历史", owner).getBody()
                .path("data").path("items").get(0).path("id").asText()).isEqualTo(c);
            assertThat(get("/materials?keyword=他人资料&status=ALL", owner).getBody()
                .path("data").path("total").asLong()).isZero();

            String tagId = get("/tags?keyword=重点", owner).getBody().path("data").path("items").get(0)
                .path("id").asText();
            assertThat(get("/materials?favorite=true&status=ALL&tagId=" + tagId, owner).getBody()
                .path("data").path("total").asLong()).isEqualTo(2);
            JsonNode pageOne = get("/materials?favorite=true&status=ALL&page=1&pageSize=1", owner)
                .getBody().path("data");
            JsonNode pageTwo = get("/materials?favorite=true&status=ALL&page=2&pageSize=1", owner)
                .getBody().path("data");
            assertThat(pageOne.path("total").asLong()).isEqualTo(2);
            assertThat(pageOne.path("items").size()).isEqualTo(1);
            assertThat(pageTwo.path("items").size()).isEqualTo(1);
            assertThat(pageOne.path("items").get(0).path("id").asText())
                .isNotEqualTo(pageTwo.path("items").get(0).path("id").asText());
            assertThat(get("/materials?favorite=true&status=ALL&page=3&pageSize=1", owner)
                .getBody().path("data").path("items").size()).isZero();
            assertThat(get("/materials?page=0", owner).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(get("/materials?pageSize=101", owner).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(get("/favorites?page=0", owner).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(get("/favorites?page=1&pageSize=1", owner).getBody().path("data").path("total").asLong())
                .isEqualTo(2);

            assertThat(exchange("/materials/" + a + "?version=1", HttpMethod.DELETE, null, owner).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(get("/favorites", owner).getBody().path("data").path("total").asLong()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM favorite WHERE user_id = ? AND material_id = ?",
                Long.class, ownerId, Long.parseLong(a))).isZero();
            assertThat(get("/materials?favorite=true&status=ALL", owner).getBody()
                .path("data").path("total").asLong()).isEqualTo(1);
            assertThat(exchange("/favorites/MATERIAL/" + a, HttpMethod.PUT, null, owner).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exchange("/favorites/MATERIAL/" + b, HttpMethod.DELETE, null, mini).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(exchange("/favorites/MATERIAL/" + b, HttpMethod.DELETE, null, owner).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(get("/favorites", owner).getBody().path("data").path("total").asLong()).isZero();
        } finally {
            clean(ownerId);
            clean(otherId);
        }
    }

    private String note(String token, String title, String body, String subject, String tag) {
        return exchange("/materials", HttpMethod.POST, Map.of("type", "NOTE", "title", title,
            "noteText", body, "subject", subject, "tags", List.of(tag)), token)
            .getBody().path("data").path("id").asText();
    }

    private long register(String username, String password) {
        ResponseEntity<JsonNode> response = rest.postForEntity(url("/auth/registrations"),
            Map.of("username", username, "password", password, "displayName", username), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("data").path("id").asLong();
    }

    private String login(String username, String password, String clientType) {
        return rest.postForEntity(url("/auth/sessions"), Map.of("username", username,
            "password", password, "clientType", clientType), JsonNode.class)
            .getBody().path("data").path("accessToken").asText();
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return exchange(path, HttpMethod.GET, null, token);
    }

    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void clean(long userId) {
        jdbc.update("DELETE FROM favorite WHERE user_id = ?", userId);
        jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON mt.material_id = m.id WHERE m.user_id = ?", userId);
        jdbc.update("DELETE FROM material WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tag WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
