package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
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
class PdfFlowIntegrationTest {
    private static final Path STORAGE = Path.of(System.getProperty("java.io.tmpdir"),
        "lms-pdf-test-" + UUID.randomUUID());

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) { properties.add("STORAGE_ROOT", STORAGE::toString); }

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
    void validatesPagesHashDuplicateAndPrivateFileAccess() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "Safe_" + suffix;
        long ownerId = register("pdf_" + suffix, password);
        long otherId = register("otherpdf_" + suffix, password);
        try {
            String owner = login("pdf_" + suffix, password);
            String other = login("otherpdf_" + suffix, password);
            byte[] valid = pdf(2, false);
            assertThat(upload("broken".getBytes(), "broken.pdf", owner, false).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(upload("%PDF-1.7\nbroken".getBytes(), "broken.pdf", owner, false).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(upload(pdf(1, true), "locked.pdf", owner, false).getBody()
                .path("error").path("code").asText()).isEqualTo("PDF_ENCRYPTED");
            assertThat(upload(pdf(101, false), "too-many.pdf", owner, false).getBody()
                .path("error").path("code").asText()).isEqualTo("PDF_PAGE_COUNT");
            assertThat(upload(new byte[30 * 1024 * 1024 + 1], "huge.pdf", owner, false).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

            ResponseEntity<JsonNode> created = upload(valid, "lecture.pdf", owner, false);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode data = created.getBody().path("data");
            String id = data.path("id").asText();
            assertThat(data.path("type").asText()).isEqualTo("PDF");
            assertThat(data.path("pageCount").asInt()).isEqualTo(2);
            assertThat(data.path("fileSize").asLong()).isEqualTo(valid.length);
            assertThat(data.has("storageKey")).isFalse();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(valid));
            assertThat(jdbc.queryForObject("SELECT file_hash FROM material WHERE id = ?", String.class, Long.parseLong(id)))
                .isEqualTo(hash);
            assertThat(jdbc.queryForObject("SELECT page_count FROM material WHERE id = ?", Integer.class, Long.parseLong(id)))
                .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT storage_key FROM material WHERE id = ?", String.class, Long.parseLong(id)))
                .matches("[0-9a-f]{2}/[0-9a-f]{32}\\.pdf");
            HttpHeaders detailHeaders = new HttpHeaders();
            detailHeaders.setBearerAuth(owner);
            ResponseEntity<JsonNode> detail = rest.exchange(url("/materials/" + id), HttpMethod.GET,
                new HttpEntity<>(detailHeaders), JsonNode.class);
            assertThat(detail.getBody().path("data").path("pageCount").asInt()).isEqualTo(2);

            ResponseEntity<byte[]> read = readFile(id, owner);
            assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(read.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
            assertThat(read.getBody()).isEqualTo(valid);
            assertThat(readFile(id, other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(readFile(id, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            ResponseEntity<JsonNode> duplicate = upload(valid, "another-name.pdf", owner, false);
            assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(duplicate.getBody().path("error").path("existingMaterialId").asText()).isEqualTo(id);
            assertThat(upload(valid, "copy.pdf", owner, true).getStatusCode()).isEqualTo(HttpStatus.CREATED);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(owner);
            assertThat(rest.exchange(url("/materials/" + id + "?version=1"), HttpMethod.DELETE,
                new HttpEntity<>(headers), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(readFile(id, owner).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material WHERE user_id = ? AND material_type = 'PDF'",
                Long.class, ownerId)).isEqualTo(2L);
        } finally {
            clean(ownerId);
            clean(otherId);
        }
    }

    private byte[] pdf(int pageCount, boolean encrypted) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pageCount; i++) document.addPage(new PDPage());
            if (encrypted) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(out);
            return out.toByteArray();
        }
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

    private ResponseEntity<JsonNode> upload(byte[] bytes, String filename, String token, boolean allowDuplicate) {
        var parts = new LinkedMultiValueMap<String, Object>();
        var fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_PDF);
        parts.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }, fileHeaders));
        parts.add("title", filename);
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

    private void clean(long userId) {
        jdbc.update("DELETE mt FROM material_tag mt JOIN material m ON mt.material_id = m.id WHERE m.user_id = ?", userId);
        jdbc.update("DELETE FROM material WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tag WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth_session WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    private String url(String path) { return "http://127.0.0.1:" + port + "/api/v1" + path; }
}
