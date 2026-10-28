package com.learningmaterial.system.integration.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Tencent GeneralBasicOCR transport. Retries and request accounting belong to the task layer. */
public final class TencentOcrClient implements OcrClient {
    private static final URI ENDPOINT = URI.create("https://ocr.tencentcloudapi.com/");
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private final String secretId, secretKey;
    private final ObjectMapper json;
    private final Clock clock;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    public TencentOcrClient(String secretId, String secretKey, ObjectMapper json, Clock clock) {
        this(secretId, secretKey, json, clock, ENDPOINT, Duration.ofSeconds(20));
    }
    // Package-private loopback transport injection for tests; no endpoint is configurable by users.
    TencentOcrClient(String secretId, String secretKey, ObjectMapper json, Clock clock, URI endpoint, Duration timeout) {
        if (!ENDPOINT.equals(endpoint) && !("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())))
            throw new IllegalArgumentException("不支持的OCR测试地址");
        this.secretId = secretId == null ? "" : secretId;
        this.secretKey = secretKey == null ? "" : secretKey;
        this.json = json; this.clock = clock; this.endpoint = endpoint; this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public String providerKey() { return "TENCENT_GENERAL_BASIC_20181119_IMAGE_V1"; }

    public Result recognize(byte[] image) {
        if (secretId.isBlank() || secretKey.isBlank()) throw error("OCR_NOT_CONFIGURED", false, false);
        if (!secretId.matches("[A-Za-z0-9_-]{1,128}") || !secretKey.matches("[A-Za-z0-9+/=_-]{1,256}"))
            throw error("OCR_CREDENTIALS_INVALID", false, false);
        if (image == null || image.length == 0 || image.length > 1024 * 1024)
            throw error("OCR_IMAGE_TOO_LARGE", false, false);
        byte[] body;
        try { body = json.writeValueAsBytes(json.createObjectNode().put("ImageBase64", Base64.getEncoder().encodeToString(image)).put("LanguageType", "zh")); }
        catch (java.io.IOException exception) { throw error("OCR_INVALID_INPUT", false, false); }
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Content-Type", CONTENT_TYPE).header("Authorization", authorization(secretId, secretKey, timestamp, body))
            .header("X-TC-Action", "GeneralBasicOCR").header("X-TC-Version", "2018-11-19")
            .header("X-TC-Timestamp", Long.toString(timestamp)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var future = http.sendAsync(request, info -> new LimitedBody(MAX_RESPONSE));
        HttpResponse<byte[]> response;
        try { response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException exception) { future.cancel(true); throw error("OCR_TIMEOUT", true, true); }
        catch (InterruptedException exception) {
            future.cancel(true); Thread.currentThread().interrupt(); throw error("OCR_INTERRUPTED", false, true);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ResponseTooLarge) throw error("OCR_INVALID_RESPONSE", false, true);
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException)
                throw error("OCR_CONNECTION_FAILED", true, false);
            if (cause instanceof java.net.http.HttpTimeoutException) throw error("OCR_TIMEOUT", true, true);
            throw error("OCR_CONNECTION_FAILED", true, true);
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) throw error("OCR_REDIRECT_REJECTED", false, true);
        if (status == 429) throw error("OCR_RATE_LIMITED", true, true);
        if (status == 401 || status == 403) throw error("OCR_AUTH_FAILED", false, true);
        if (status >= 500) throw error("OCR_PROVIDER_UNAVAILABLE", true, true);
        if (status != 200) throw error("OCR_PROVIDER_FAILED", false, true);
        return parse(response.body());
    }

    private Result parse(byte[] bytes) {
        JsonNode response;
        try { response = json.readTree(bytes).path("Response"); }
        catch (java.io.IOException | RuntimeException exception) { throw error("OCR_INVALID_RESPONSE", false, true); }
        if (!response.isObject()) throw error("OCR_INVALID_RESPONSE", false, true);
        String id = response.path("RequestId").textValue();
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw error("OCR_INVALID_RESPONSE", false, true);
        if (response.has("Error")) {
            String code = response.path("Error").path("Code").textValue();
            if (code == null) throw error("OCR_INVALID_RESPONSE", false, true);
            if ("FailedOperation.ImageNoText".equals(code)) return new Result("", null, id);
            if (code.startsWith("AuthFailure")) throw error("OCR_AUTH_FAILED", false, true);
            if ("LimitExceeded.TooLargeFileError".equals(code)) throw error("OCR_IMAGE_TOO_LARGE", false, true);
            if (code.startsWith("RequestLimitExceeded") || code.equals("LimitExceeded")) throw error("OCR_RATE_LIMITED", true, true);
            if ("FailedOperation.EngineRecognizeTimeout".equals(code)) throw error("OCR_TIMEOUT", true, true);
            if (code.startsWith("InternalError")) throw error("OCR_PROVIDER_UNAVAILABLE", true, true);
            if ("FailedOperation.UnOpenError".equals(code)) throw error("OCR_SERVICE_NOT_ENABLED", false, true);
            throw error("OCR_PROVIDER_FAILED", false, true);
        }
        JsonNode lines = response.path("TextDetections");
        if (!lines.isArray() || lines.size() > 2000) throw error("OCR_INVALID_RESPONSE", false, true);
        StringBuilder text = new StringBuilder(); double sum = 0; int count = 0; boolean known = true;
        for (JsonNode line : lines) {
            if (!line.path("DetectedText").isTextual()) throw error("OCR_INVALID_RESPONSE", false, true);
            String value = line.path("DetectedText").textValue();
            if (value.isBlank()) continue;
            if (text.length() + value.length() + (text.isEmpty() ? 0 : 1) > 60_000)
                throw error("OCR_TEXT_LIMIT_EXCEEDED", false, true);
            if (!text.isEmpty()) text.append('\n'); text.append(value);
            JsonNode confidence = line.path("Confidence");
            if (confidence.isMissingNode() || confidence.isNull()) known = false;
            else {
                double score = confidence.doubleValue();
                if (!confidence.isNumber() || !Double.isFinite(score) || score < 0 || score > 100)
                    throw error("OCR_INVALID_RESPONSE", false, true);
                sum += score;
            }
            count++;
        }
        return new Result(text.toString(), known && count > 0 ? sum / count / 100 : null, id);
    }

    static String authorization(String id, String key, long timestamp, byte[] payload) {
        String date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(timestamp));
        String scope = date + "/ocr/tc3_request";
        String signedHeaders = "content-type;host;x-tc-action";
        String headers = "content-type:" + CONTENT_TYPE + "\nhost:ocr.tencentcloudapi.com\nx-tc-action:generalbasicocr\n";
        String canonical = "POST\n/\n\n" + headers + "\n" + signedHeaders + "\n" + sha(payload);
        String toSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] dateKey = hmac(("TC3" + key).getBytes(StandardCharsets.UTF_8), date);
        byte[] serviceKey = hmac(dateKey, "ocr"); byte[] signingKey = hmac(serviceKey, "tc3_request");
        return "TC3-HMAC-SHA256 Credential=" + id + "/" + scope + ", SignedHeaders=" + signedHeaders
            + ", Signature=" + HexFormat.of().formatHex(hmac(signingKey, toSign));
    }
    private static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (GeneralSecurityException exception) { throw new IllegalStateException("SHA-256不可用"); }
    }
    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("HMAC不可用"); }
    }
    private static OcrException error(String code, boolean retryable, boolean dispatched) { return new OcrException(code, retryable, dispatched); }
    private static final class ResponseTooLarge extends RuntimeException { }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit; private int size; private Flow.Subscription subscription; private boolean ended;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            if (ended) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - size) {
                    ended = true; subscription.cancel(); delegate.onError(new ResponseTooLarge()); return;
                }
                size += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!ended) { ended = true; delegate.onError(error); } }
        public void onComplete() { if (!ended) { ended = true; delegate.onComplete(); } }
    }
}
