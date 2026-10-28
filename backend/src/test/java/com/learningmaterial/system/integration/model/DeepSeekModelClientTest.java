package com.learningmaterial.system.integration.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekModelClientTest {
    final ObjectMapper json = new ObjectMapper();
    final AtomicInteger calls = new AtomicInteger();
    HttpServer server;
    URI endpoint;
    volatile int status;
    volatile String response;
    volatile String requestBody;
    volatile String authorization;
    volatile long headerDelay;
    volatile long bodyDelay;

    @BeforeEach
    void start() throws Exception {
        status = 200;
        response = valid();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(
                "/chat/completions",
                exchange -> {
                    calls.incrementAndGet();
                    requestBody =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    try {
                        Thread.sleep(headerDelay);
                        byte[] body = response.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, body.length);
                        exchange.getResponseBody().flush();
                        Thread.sleep(bodyDelay);
                        exchange.getResponseBody().write(body);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        endpoint =
                URI.create(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    DeepSeekModelClient client() {
        return new DeepSeekModelClient("test-only-key", json, endpoint, Duration.ofSeconds(2));
    }

    ModelClient.Result generate() {
        return client().generate("deepseek-flash", "Output JSON only", "自制测试文字");
    }

    String valid() throws Exception {
        var root =
                json.createObjectNode().put("id", "test-request-1").put("model", "deepseek-flash");
        root.putArray("choices")
                .addObject()
                .put("finish_reason", "stop")
                .putObject("message")
                .put("role", "assistant")
                .put("content", "{\"summary\":\"测试\"}");
        root.putObject("usage").put("prompt_tokens", 50).put("completion_tokens", 30);
        return json.writeValueAsString(root);
    }

    ModelException error(String code) {
        ModelException failure = assertThrows(ModelException.class, this::generate);
        assertEquals(code, failure.code());
        assertEquals(code, failure.getMessage());
        assertNull(failure.getCause());
        return failure;
    }

    @Test
    void sendsJsonModeWithoutToolsAndRecordsActualModelAndUsage() throws Exception {
        ModelClient.Result result = generate();
        var body = json.readTree(requestBody);
        assertEquals("Bearer test-only-key", authorization);
        assertEquals("deepseek-flash", body.path("model").asText());
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertFalse(body.path("stream").asBoolean(true));
        assertEquals(4096, body.path("max_tokens").asInt());
        assertFalse(body.has("tools"));
        assertEquals("自制测试文字", body.path("messages").get(1).path("content").asText());
        assertEquals("deepseek-flash", result.model());
        assertEquals("test-request-1", result.requestId());
        assertEquals(50L, result.inputTokens());
        assertEquals(30L, result.outputTokens());
        assertEquals(1, calls.get());
    }

    @Test
    void missingCredentialsNeverSend() {
        var failure =
                assertThrows(
                        ModelException.class,
                        () ->
                                new DeepSeekModelClient("", json, endpoint, Duration.ofSeconds(1))
                                        .generate("deepseek-flash", "JSON", "text"));
        assertEquals("MODEL_NOT_CONFIGURED", failure.code());
        assertFalse(failure.dispatched());
        assertEquals(0, calls.get());
    }

    @Test
    void authFailureDoesNotExposeProviderBodyOrRetry() {
        status = 401;
        response = "test-only-key SECRET provider detail";
        var failure = error("MODEL_AUTH_FAILED");
        assertFalse(failure.retryable());
        assertTrue(failure.dispatched());
        assertEquals(1, calls.get());
    }

    @Test
    void rateLimitIsRetryableButTransportDoesNotRetry() {
        status = 429;
        assertTrue(error("MODEL_RATE_LIMITED").retryable());
        assertEquals(1, calls.get());
    }

    @Test
    void serverFailureIsRetryable() {
        status = 503;
        assertTrue(error("MODEL_PROVIDER_UNAVAILABLE").retryable());
    }

    @Test
    void redirectIsRejected() {
        status = 302;
        error("MODEL_REDIRECT_REJECTED");
        assertEquals(1, calls.get());
    }

    @Test
    void truncatedOutputIsNotAccepted() {
        response = response.replace("\"stop\"", "\"length\"");
        error("MODEL_OUTPUT_TRUNCATED");
    }

    @Test
    void toolCallsAreNeverAccepted() {
        response =
                response.replace(
                        "\"role\":\"assistant\"", "\"tool_calls\":[{}],\"role\":\"assistant\"");
        error("MODEL_INVALID_RESPONSE");
    }

    @Test
    void emptyContentIsRejected() throws Exception {
        var root = json.readTree(response);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        root.path("choices").get(0).path("message"))
                .put("content", " ");
        response = json.writeValueAsString(root);
        error("MODEL_EMPTY_RESULT");
    }

    @Test
    void missingUsageRemainsUnknown() throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(response);
        root.remove("usage");
        response = json.writeValueAsString(root);
        assertNull(generate().inputTokens());
    }

    @Test
    void negativeUsageIsRejected() {
        response = response.replace("\"prompt_tokens\":50", "\"prompt_tokens\":-1");
        error("MODEL_INVALID_RESPONSE");
    }

    @Test
    void malformedAndDuplicateAndTrailingJsonAreRejected() throws Exception {
        for (String body :
                new String[] {
                    "not JSON",
                    valid() + "{}",
                    valid().replace("\"id\":", "\"id\":\"duplicate\",\"id\":")
                }) {
            response = body;
            error("MODEL_INVALID_RESPONSE");
        }
    }

    @Test
    void responseLimitIsEnforced() {
        response = "x".repeat(2 * 1024 * 1024 + 1);
        error("MODEL_INVALID_RESPONSE");
    }

    @Test
    void delayedHeadersHaveTotalTimeout() {
        headerDelay = 1500;
        var failure =
                assertThrows(
                        ModelException.class,
                        () ->
                                new DeepSeekModelClient(
                                                "test-key", json, endpoint, Duration.ofMillis(100))
                                        .generate("deepseek-flash", "JSON", "text"));
        assertEquals("MODEL_TIMEOUT", failure.code());
        assertTrue(failure.dispatched());
    }

    @Test
    void delayedBodyHasTotalTimeout() {
        bodyDelay = 1500;
        long start = System.nanoTime();
        var failure =
                assertThrows(
                        ModelException.class,
                        () ->
                                new DeepSeekModelClient(
                                                "test-key", json, endpoint, Duration.ofMillis(100))
                                        .generate("deepseek-flash", "JSON", "text"));
        assertEquals("MODEL_TIMEOUT", failure.code());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
    }

    @Test
    void knownAuthRejectionWithStalledBodyNeverBecomesRetryableTimeout() {
        status = 401;
        bodyDelay = 1500;
        var failure =
                assertThrows(
                        ModelException.class,
                        () ->
                                new DeepSeekModelClient(
                                                "test-key", json, endpoint, Duration.ofMillis(300))
                                        .generate("deepseek-flash", "JSON", "text"));
        assertEquals("MODEL_AUTH_FAILED", failure.code());
        assertFalse(failure.retryable());
    }

    @Test
    void knownRedirectWithStalledBodyIsRejected() {
        status = 302;
        bodyDelay = 1500;
        var failure =
                assertThrows(
                        ModelException.class,
                        () ->
                                new DeepSeekModelClient(
                                                "test-key", json, endpoint, Duration.ofMillis(300))
                                        .generate("deepseek-flash", "JSON", "text"));
        assertEquals("MODEL_REDIRECT_REJECTED", failure.code());
        assertFalse(failure.retryable());
    }

    @Test
    void oversizedInputNeverSends() {
        var failure =
                assertThrows(
                        ModelException.class,
                        () -> client().generate("deepseek-flash", "JSON", "x".repeat(200_001)));
        assertEquals("MODEL_INPUT_LIMIT", failure.code());
        assertFalse(failure.dispatched());
        assertEquals(0, calls.get());
    }
}
