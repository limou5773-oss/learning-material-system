package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class ImageFlowIntegrationTest {
    private static final Path STORAGE = Path.of(System.getProperty("java.io.tmpdir"),
        "lms-image-test-" + UUID.randomUUID());

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("STORAGE_ROOT", STORAGE::toString);
    }

    @AfterAll
    static void removeStorage() throws Exception {
        if (Files.exists(STORAGE)) {
            try (var paths = Files.walk(STORAGE)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void validatesImagesAndOnlyOwnerCanReadStoredBytes() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Safe_" + suffix;
        long ownerId = register("image_" + suffix, password);
        long otherId = register("otherimage_" + suffix, password);
        try {
            String owner = login("image_" + suffix, password);
            String other = login("otherimage_" + suffix, password);
            byte[] png = png();

            assertThat(upload(png, "photo.gif", "image/png", "图片", owner, false).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(upload("not a png".getBytes(), "photo.png", "image/png", "图片", owner, false).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(upload(png, "photo.png", "image/jpeg", "图片", owner, false).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(upload(new byte[10 * 1024 * 1024 + 1], "big.png", "image/png", "图片", owner, false)
                .getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

            ResponseEntity<JsonNode> created = upload(png, "photo.png", "image/png", "课堂板书", owner, false);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode data = created.getBody().path("data");
            String id = data.path("id").asText();
            assertThat(data.path("type").asText()).isEqualTo("IMAGE");
            assertThat(data.path("fileSize").asLong()).isEqualTo(png.length);
            assertThat(data.has("storageKey")).isFalse();
            assertThat(data.path("tags").get(0).path("name").asText()).isEqualTo("板书");
            try (var paths = Files.walk(STORAGE)) {
                assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(1);
            }

            ResponseEntity<byte[]> file = readFile(id, owner);
            assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(file.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
            assertThat(file.getHeaders().getCacheControl()).contains("no-store");
            assertThat(file.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(file.getBody()).isEqualTo(png);
            assertThat(readFile(id, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(readFile(id, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            ResponseEntity<JsonNode> duplicate = upload(png, "photo.png", "image/png", "重复", owner, false);
            assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(duplicate.getBody().path("error").path("existingMaterialId").asText()).isEqualTo(id);
            assertThat(upload(png, "photo.png", "image/png", "明确保存副本", owner, true).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
            byte[] jpeg = jpeg();
            ResponseEntity<JsonNode> jpegCreated = upload(jpeg, "photo.jpeg", "image/jpeg", "另一张图片", owner, false);
            assertThat(jpegCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(readFile(jpegCreated.getBody().path("data").path("id").asText(), owner)
                .getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
            assertThat(upload(png, "photo.png", "image/png", "他人的同图", other, false).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

            assertThat(exchangeJson("/materials/" + id, HttpMethod.PATCH,
                Map.of("version", 1, "status", "ARCHIVED"), owner).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(readFile(id, owner).getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<JsonNode> removed = exchangeJson("/materials/" + id + "?version=2", HttpMethod.DELETE,
                null, owner);
            assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(readFile(id, owner).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material WHERE user_id = ? AND material_type = 'IMAGE'",
                Long.class, ownerId)).isEqualTo(3L);
        } finally {
            clean(ownerId);
            clean(otherId);
        }
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xff336699);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", out)).isTrue();
        return out.toByteArray();
    }

    private byte[] jpeg() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xff775533);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "jpeg", out)).isTrue();
        return out.toByteArray();
    }

    private long register(String username, String password) {
        ResponseEntity<JsonNode> response = rest.postForEntity(url("/auth/registrations"),
            Map.of("username", username, "password", password, "displayName", username), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("data").path("id").asLong();
    }

    private String login(String username, String password) {
        ResponseEntity<JsonNode> response = rest.postForEntity(url("/auth/sessions"),
            Map.of("username", username, "password", password, "clientType", "WEB"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("data").path("accessToken").asText();
    }

    private ResponseEntity<JsonNode> upload(byte[] bytes, String filename, String mime,
                                             String title, String token, boolean allowDuplicate) {
        var parts = new LinkedMultiValueMap<String, Object>();
        var fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(mime));
        parts.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }, fileHeaders));
        parts.add("title", title);
        parts.add("subject", "数学");
        parts.add("tagsJson", "[\"板书\"]");
        parts.add("allowDuplicate", Boolean.toString(allowDuplicate));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        return rest.postForEntity(url("/materials/files"), new HttpEntity<>(parts, headers), JsonNode.class);
    }

    private ResponseEntity<byte[]> readFile(String id, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url("/materials/" + id + "/file"), HttpMethod.GET,
            new HttpEntity<>(headers), byte[].class);
    }

    private ResponseEntity<JsonNode> exchangeJson(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void clean(long userId) {
        jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON mt.material_id = m.id WHERE m.user_id = ?", userId);
        jdbc.update("DELETE FROM material WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tag WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
