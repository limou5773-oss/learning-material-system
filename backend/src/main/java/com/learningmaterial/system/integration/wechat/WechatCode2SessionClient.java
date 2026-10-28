package com.learningmaterial.system.integration.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.WechatIdentity;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;

/** Exchanges a single-use wx.login code; provider secrets and session keys never leave this adapter. */
public final class WechatCode2SessionClient implements WechatLoginClient {
    private static final URI ENDPOINT = URI.create("https://api.weixin.qq.com/sns/jscode2session");
    private static final int MAX_RESPONSE = 16 * 1024;
    private final boolean enabled;
    private final String appId;
    private final String appSecret;
    private final ObjectMapper json;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    public WechatCode2SessionClient(boolean enabled, String appId, String appSecret, ObjectMapper json) {
        this(enabled, appId, appSecret, json, ENDPOINT, Duration.ofSeconds(10));
    }

    // Package-private endpoint injection is used only by loopback transport tests.
    WechatCode2SessionClient(boolean enabled, String appId, String appSecret, ObjectMapper json,
                            URI endpoint, Duration timeout) {
        this.enabled = enabled;
        this.appId = appId;
        this.appSecret = appSecret;
        this.json = json;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public WechatIdentity exchange(String code) {
        if (!enabled || appId.isBlank() || appSecret.isBlank())
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_NOT_CONFIGURED", "微信登录尚未配置");
        URI uri = URI.create(endpoint + "?appid=" + encode(appId) + "&secret=" + encode(appSecret)
            + "&js_code=" + encode(code) + "&grant_type=authorization_code");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        var future = http.sendAsync(request, response -> new LimitedBody());
        HttpResponse<byte[]> response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_TIMEOUT", "微信登录请求超时，请重新获取登录凭证");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_INTERRUPTED", "微信登录请求已中断");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ResponseTooLarge) throw invalidResponse();
            if (exception.getCause() instanceof HttpTimeoutException)
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_TIMEOUT", "微信登录请求超时，请重新获取登录凭证");
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_CONNECTION_FAILED", "无法连接微信登录服务，请重新获取登录凭证");
        }
        int status = response.statusCode();
        if (status == 429) throw rateLimited();
        if (status == 401 || status == 403) throw authenticationFailed();
        if (status >= 500) throw unavailable();
        if (status != 200)
            throw error(HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_FAILED", "微信登录服务返回异常");
        return parse(response.body());
    }

    private WechatIdentity parse(byte[] bytes) {
        JsonNode response;
        try {
            response = json.readTree(bytes);
        } catch (IOException exception) {
            throw invalidResponse();
        }
        if (response == null || !response.isObject()) throw invalidResponse();
        JsonNode code = response.get("errcode");
        if (code != null) {
            if (!code.isIntegralNumber() || !code.canConvertToInt()) throw invalidResponse();
            switch (code.intValue()) {
                case 0 -> { }
                case 40029, 40163 -> throw error(HttpStatus.UNPROCESSABLE_ENTITY, "WECHAT_CODE_INVALID",
                    "微信登录凭证无效或已使用，请重新登录");
                case -1 -> throw unavailable();
                case 45009, 45011 -> throw rateLimited();
                case 40001, 40002, 40013, 40125, 41002, 41004, 48001 -> throw authenticationFailed();
                default -> throw error(HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_FAILED", "微信登录服务返回异常");
            }
        }
        JsonNode openId = response.path("openid");
        if (!openId.isTextual() || openId.textValue().isBlank()
                || openId.textValue().length() > 128 || openId.textValue().chars().anyMatch(c -> c > 127))
            throw invalidResponse();
        return new WechatIdentity(appId, openId.textValue());
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }
    private static ApiException invalidResponse() {
        return error(HttpStatus.BAD_GATEWAY, "WECHAT_INVALID_RESPONSE", "微信登录服务响应格式错误");
    }
    private static ApiException unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_PROVIDER_UNAVAILABLE", "微信登录服务繁忙，请稍后重新登录");
    }
    private static ApiException rateLimited() {
        return error(HttpStatus.TOO_MANY_REQUESTS, "WECHAT_RATE_LIMITED", "微信登录请求过于频繁，请稍后重新登录");
    }
    private static ApiException authenticationFailed() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_AUTH_FAILED", "微信登录服务配置或授权异常");
    }

    private static final class ResponseTooLarge extends RuntimeException { }

    /** Bounds allocation while keeping the HTTP future active until the complete body arrives. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;
        private boolean ended;

        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
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
        public void onError(Throwable error) { if (!ended) { ended = true; delegate.onError(error); } }
        public void onComplete() { if (!ended) { ended = true; delegate.onComplete(); } }
    }
}
