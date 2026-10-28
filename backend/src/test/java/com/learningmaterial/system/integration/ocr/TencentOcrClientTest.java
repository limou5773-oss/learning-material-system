package com.learningmaterial.system.integration.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TencentOcrClientTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>(), auth = new AtomicReference<>();
    private final AtomicReference<String> action = new AtomicReference<>(), timestamp = new AtomicReference<>();
    private boolean delayBody;
    private static final String GOOD = """
        {"Response":{"RequestId":"test-request-1","TextDetections":[
          {"DetectedText":"光合作用","Confidence":98},
          {"DetectedText":"Photosynthesis","Confidence":50}]}}
        """;

    private TencentOcrClient client(int status, String response, Duration timeout, long delay) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            action.set(exchange.getRequestHeaders().getFirst("X-TC-Action"));
            timestamp.set(exchange.getRequestHeaders().getFirst("X-TC-Timestamp"));
            try {
                if (delay > 0 && !delayBody) Thread.sleep(delay);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                if (status == 302) exchange.getResponseHeaders().set("Location", "/must-not-follow");
                exchange.sendResponseHeaders(status, bytes.length);
                if (delay > 0 && delayBody) Thread.sleep(delay);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        return new TencentOcrClient("AKID_TEST_ONLY", "test-secret", json, CLOCK,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), timeout);
    }
    private TencentOcrClient client(int status, String response) throws Exception {
        return client(status, response, Duration.ofSeconds(2), 0);
    }
    @AfterEach void close() { if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); }

    @Test void signsActualImageBytesAndParsesChineseEnglishAndConfidence() throws Exception {
        var result = client(200, GOOD).recognize(new byte[]{1, 2, 3});
        assertThat(result.text()).isEqualTo("光合作用\nPhotosynthesis");
        assertThat(result.confidence()).isEqualTo(0.74);
        assertThat(result.requestId()).isEqualTo("test-request-1");
        assertThat(json.readTree(body.get()).path("ImageBase64").asText()).isEqualTo("AQID");
        assertThat(json.readTree(body.get()).path("LanguageType").asText()).isEqualTo("zh");
        assertThat(json.readTree(body.get()).has("ImageUrl")).isFalse();
        assertThat(action.get()).isEqualTo("GeneralBasicOCR");
        assertThat(timestamp.get()).isEqualTo(Long.toString(CLOCK.instant().getEpochSecond()));
        assertThat(auth.get()).startsWith("TC3-HMAC-SHA256 Credential=AKID_TEST_ONLY/2026-10-25/ocr/tc3_request, ");
        assertThat(auth.get()).doesNotContain("test-secret");
    }

    @Test void missingCredentialsNeverSendAnyImage() {
        var client = new TencentOcrClient("", "", json, CLOCK);
        assertThatThrownBy(() -> client.recognize(new byte[]{1})).isInstanceOfSatisfying(OcrException.class, error -> {
            assertThat(error.code()).isEqualTo("OCR_NOT_CONFIGURED"); assertThat(error.dispatched()).isFalse();
        });
        assertThat(requests).hasValue(0);
    }

    @Test void rejectsAuthErrorsWithoutLeakingProviderMessageOrRetryingInsideTransport() throws Exception {
        var client = client(200, "{\"Response\":{\"RequestId\":\"safe-id\",\"Error\":{\"Code\":\"AuthFailure.SignatureFailure\",\"Message\":\"secret-private-text\"}}}");
        assertThatThrownBy(() -> client.recognize(new byte[]{1})).isInstanceOfSatisfying(OcrException.class, error -> {
            assertThat(error.code()).isEqualTo("OCR_AUTH_FAILED"); assertThat(error.retryable()).isFalse();
            assertThat(error.dispatched()).isTrue(); assertThat(error.toString()).doesNotContain("secret-private-text");
        });
        assertThat(requests).hasValue(1);
    }

    @Test void distinguishesTransientAndEmptyResults() throws Exception {
        var client = client(429, "{}");
        assertThatThrownBy(() -> client.recognize(new byte[]{1})).isInstanceOfSatisfying(OcrException.class, error -> {
            assertThat(error.code()).isEqualTo("OCR_RATE_LIMITED"); assertThat(error.retryable()).isTrue();
        });
    }

    @Test void missingConfidenceRemainsUnknown() throws Exception {
        var client = client(200, "{\"Response\":{\"RequestId\":\"safe\",\"TextDetections\":[{\"DetectedText\":\"学习\"}]}}");
        assertThat(client.recognize(new byte[]{1}).confidence()).isNull();
    }

    @Test void anEmptyDetectionArrayIsNotInventedText() throws Exception {
        var result = client(200, "{\"Response\":{\"RequestId\":\"empty-id\",\"TextDetections\":[]}}")
            .recognize(new byte[]{1});
        assertThat(result.text()).isEmpty(); assertThat(result.confidence()).isNull();
    }

    @Test void signatureMatchesAnIndependentSyntheticProtocolVector() {
        // Independently calculated with Python hashlib/hmac and the published TC3 protocol.
        byte[] payload = "{\"ImageBase64\":\"AQID\",\"LanguageType\":\"zh\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(TencentOcrClient.authorization("AKID_TEST_ONLY", "test-secret", CLOCK.instant().getEpochSecond(), payload))
            .endsWith("Signature=0677f5c175716a686db87bd67187f25ed208fdd1f37cc4f2ebb4c0465662e99f");
    }

    @Test void totalTimeoutAlsoCoversAStalledResponseBody() throws Exception {
        delayBody = true;
        var client = client(200, GOOD, Duration.ofMillis(100), 3000);
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.recognize(new byte[]{1})).isInstanceOfSatisfying(OcrException.class,
            error -> assertThat(error.code()).isEqualTo("OCR_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test void rejectsMalformedTypesAndOutOfRangeConfidence() throws Exception {
        var client = client(200, "{\"Response\":{\"RequestId\":\"safe\",\"TextDetections\":[{\"DetectedText\":\"学习\",\"Confidence\":101}]}}");
        assertThatThrownBy(() -> client.recognize(new byte[]{1})).isInstanceOfSatisfying(OcrException.class,
            error -> assertThat(error.code()).isEqualTo("OCR_INVALID_RESPONSE"));
    }

    @Test void refusesRedirects() throws Exception {
        var client = client(302, "{}");
        assertThatThrownBy(() -> client.recognize(new byte[]{1})).isInstanceOfSatisfying(OcrException.class,
            error -> assertThat(error.code()).isEqualTo("OCR_REDIRECT_REJECTED"));
        assertThat(requests).hasValue(1);
    }

    @Test void responseSizeAndTotalBodyTimeAreBounded() throws Exception {
        var client = client(200, "x".repeat(2 * 1024 * 1024 + 1));
        assertThatThrownBy(() -> client.recognize(new byte[]{1})).isInstanceOfSatisfying(OcrException.class,
            error -> assertThat(error.code()).isEqualTo("OCR_INVALID_RESPONSE"));
    }

    @Test void timeoutsAreUnknownDispatchAndDoNotLeakTransportDetails() throws Exception {
        var client = client(200, GOOD, Duration.ofMillis(100), 1000);
        assertThatThrownBy(() -> client.recognize(new byte[]{1})).isInstanceOfSatisfying(OcrException.class, error -> {
            assertThat(error.code()).isEqualTo("OCR_TIMEOUT"); assertThat(error.dispatched()).isTrue();
            assertThat(error.retryable()).isTrue(); assertThat(error.getCause()).isNull();
        });
    }
}
