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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class AuthFlowIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void registrationLoginAndLogoutUseRevocableHashedSessions() {
        String username = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Safe1_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Long userId = null;
        try {
            assertThat(getMe(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            ResponseEntity<JsonNode> roleAttempt = post("/auth/registrations",
                Map.of("username", username, "password", password, "displayName", "Test", "role", "ADMIN"));
            assertThat(roleAttempt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            ResponseEntity<JsonNode> invalidPassword = post("/auth/registrations",
                Map.of("username", username, "password", "密码".repeat(30), "displayName", "Test"));
            assertThat(invalidPassword.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            ResponseEntity<JsonNode> registration = post("/auth/registrations",
                Map.of("username", username, "password", password, "displayName", "Test"));
            assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode created = registration.getBody().path("data");
            assertThat(created.path("role").asText()).isEqualTo("USER");
            assertThat(created.path("id").asText()).matches("[1-9][0-9]*");
            userId = created.path("id").asLong();

            assertThat(post("/auth/registrations",
                Map.of("username", username, "password", password, "displayName", "Again"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(post("/auth/sessions",
                Map.of("username", username, "password", "wrong-pass", "clientType", "WEB"))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            ResponseEntity<JsonNode> login = post("/auth/sessions",
                Map.of("username", username, "password", password, "clientType", "WEB"));
            assertThat(login.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String token = login.getBody().path("data").path("accessToken").asText();
            assertThat(token).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
            assertThat(login.getBody().path("data").path("tokenType").asText()).isEqualTo("Bearer");

            String passwordHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?",
                String.class, userId);
            String tokenHash = jdbc.queryForObject("SELECT token_hash FROM auth_session WHERE user_id = ?",
                String.class, userId);
            assertThat(passwordHash).startsWith("$2").isNotEqualTo(password);
            assertThat(tokenHash).hasSize(64).isNotEqualTo(token);

            ResponseEntity<JsonNode> me = getMe(token);
            assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(me.getBody().path("data").path("id").asLong()).isEqualTo(userId);
            assertThat(me.getBody().path("data").has("passwordHash")).isFalse();

            ResponseEntity<JsonNode> miniLogin = post("/auth/sessions",
                Map.of("username", username, "password", password, "clientType", "MINIPROGRAM"));
            assertThat(miniLogin.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String miniToken = miniLogin.getBody().path("data").path("accessToken").asText();
            assertThat(miniToken).isNotEqualTo(token);
            assertThat(miniLogin.getBody().path("data").path("user").path("id").asLong()).isEqualTo(userId);
            assertThat(getMe(miniToken).getBody().path("data").path("id").asLong()).isEqualTo(userId);
            assertThat(jdbc.queryForObject("SELECT client_type FROM auth_session WHERE token_hash = SHA2(?, 256)",
                String.class, miniToken)).isEqualTo("MINIPROGRAM");

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            ResponseEntity<Void> logout = rest.exchange(url("/auth/sessions/current"), HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
            assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(getMe(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(getMe(miniToken).getStatusCode()).isEqualTo(HttpStatus.OK);

            headers.setBearerAuth(miniToken);
            assertThat(rest.exchange(url("/auth/sessions/current"), HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(getMe(miniToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            if (userId != null) {
                jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
            }
        }
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return rest.postForEntity(url(path), body, JsonNode.class);
    }

    private ResponseEntity<JsonNode> getMe(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url("/users/me"), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + "/api/v1" + path;
    }
}
