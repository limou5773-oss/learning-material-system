package com.learningmaterial.system.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Transport only; source validation, retries and publication belong to the task service. */
public final class DeepSeekModelClient implements ModelClient {
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private final String apiKey;
    private final ObjectMapper json;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    public DeepSeekModelClient(String apiKey, ObjectMapper json) {
        this(apiKey, json, ENDPOINT, Duration.ofSeconds(45));
    }

    // Test-only loopback injection; no arbitrary endpoint is exposed in configuration.
    DeepSeekModelClient(String apiKey, ObjectMapper json, URI endpoint, Duration timeout) {
        if (!ENDPOINT.equals(endpoint)
                && !("http".equals(endpoint.getScheme())
                        && "127.0.0.1".equals(endpoint.getHost()))) {
            throw new IllegalArgumentException("不支持的模型测试地址");
        }
        this.apiKey = apiKey == null ? "" : apiKey;
        this.json =
                json.copy()
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public String providerKey() {
        return "DEEPSEEK_CHAT_V1";
    }

    @Override
    public Result generate(String model, String systemPrompt, String userPrompt) {
        if (apiKey.isBlank()) throw error("MODEL_NOT_CONFIGURED", false, false);
        if (!apiKey.matches("[A-Za-z0-9._-]{1,512}"))
            throw error("MODEL_CREDENTIALS_INVALID", false, false);
        if (!identifier(model, 96)) throw error("MODEL_CONFIG_INVALID", false, false);
        if (systemPrompt == null
                || systemPrompt.isBlank()
                || systemPrompt.length() > 20_000
                || userPrompt == null
                || userPrompt.isBlank()
                || userPrompt.length() > 200_000) throw error("MODEL_INPUT_LIMIT", false, false);
        byte[] body;
        try {
            var payload =
                    json.createObjectNode()
                            .put("model", model)
                            .put("stream", false)
                            .put("max_tokens", 4096)
                            .put("temperature", 0.2);
            payload.putObject("thinking").put("type", "disabled");
            payload.putObject("response_format").put("type", "json_object");
            var messages = payload.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);
            body = json.writeValueAsBytes(payload);
        } catch (java.io.IOException exception) {
            throw error("MODEL_INVALID_INPUT", false, false);
        }
        if (body.length > 1024 * 1024) throw error("MODEL_INPUT_LIMIT", false, false);
        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        AtomicInteger receivedStatus = new AtomicInteger();
        var future =
                http.sendAsync(
                        request,
                        info -> {
                            receivedStatus.set(info.statusCode());
                            checkStatus(info.statusCode());
                            return new LimitedBody();
                        });
        HttpResponse<byte[]> response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            checkStatus(receivedStatus.get());
            throw error("MODEL_TIMEOUT", true, true);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw error("MODEL_INTERRUPTED", false, true);
        } catch (ExecutionException exception) {
            checkStatus(receivedStatus.get());
            Throwable cause = exception.getCause();
            if (cause instanceof ResponseTooLarge)
                throw error("MODEL_INVALID_RESPONSE", false, true);
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException)
                throw error("MODEL_CONNECTION_FAILED", true, false);
            if (cause instanceof java.net.http.HttpTimeoutException)
                throw error("MODEL_TIMEOUT", true, true);
            throw error("MODEL_CONNECTION_FAILED", true, true);
        }
        checkStatus(response.statusCode());
        return parse(response.body());
    }

    private static void checkStatus(int status) {
        if (status == 0 || status == 200) return;
        if (status >= 300 && status < 400) throw error("MODEL_REDIRECT_REJECTED", false, true);
        if (status == 401 || status == 403) throw error("MODEL_AUTH_FAILED", false, true);
        if (status == 402) throw error("MODEL_QUOTA_EXCEEDED", false, true);
        if (status == 429) throw error("MODEL_RATE_LIMITED", true, true);
        if (status >= 500) throw error("MODEL_PROVIDER_UNAVAILABLE", true, true);
        throw error("MODEL_REQUEST_REJECTED", false, true);
    }

    private Result parse(byte[] bytes) {
        try {
            JsonNode root = json.readTree(bytes);
            if (root == null
                    || !root.isObject()
                    || !identifier(root.path("id").textValue(), 128)
                    || !identifier(root.path("model").textValue(), 96)) invalid();
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() != 1) invalid();
            JsonNode choice = choices.get(0);
            String reason = choice.path("finish_reason").textValue();
            if ("length".equals(reason)) throw error("MODEL_OUTPUT_TRUNCATED", false, true);
            if ("content_filter".equals(reason)) throw error("MODEL_CONTENT_FILTERED", false, true);
            if (!"stop".equals(reason)) invalid();
            JsonNode message = choice.path("message");
            if (!"assistant".equals(message.path("role").textValue())
                    || message.has("tool_calls") && !message.path("tool_calls").isNull()
                    || message.has("function_call") && !message.path("function_call").isNull())
                invalid();
            String content = message.path("content").textValue();
            if (content == null || content.isBlank())
                throw error("MODEL_EMPTY_RESULT", false, true);
            if (content.length() > 100_000) invalid();
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull() && !usage.isObject()) invalid();
            return new Result(
                    content,
                    root.path("model").textValue(),
                    root.path("id").textValue(),
                    tokenCount(usage.path("prompt_tokens")),
                    tokenCount(usage.path("completion_tokens")));
        } catch (ModelException exception) {
            throw exception;
        } catch (java.io.IOException | RuntimeException exception) {
            throw error("MODEL_INVALID_RESPONSE", false, true);
        }
    }

    private static Long tokenCount(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            invalid();
        return value.longValue();
    }

    private static boolean identifier(String value, int max) {
        return value != null && value.length() <= max && value.matches("[A-Za-z0-9._:-]+");
    }

    private static void invalid() {
        throw error("MODEL_INVALID_RESPONSE", false, true);
    }

    private static ModelException error(String code, boolean retryable, boolean dispatched) {
        return new ModelException(code, retryable, dispatched);
    }

    private static final class ResponseTooLarge extends RuntimeException {}

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private int size;
        private Flow.Subscription subscription;
        private boolean ended;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (ended) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE - size) {
                    ended = true;
                    subscription.cancel();
                    delegate.onError(new ResponseTooLarge());
                    return;
                }
                size += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            if (!ended) {
                ended = true;
                delegate.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!ended) {
                ended = true;
                delegate.onComplete();
            }
        }
    }
}
