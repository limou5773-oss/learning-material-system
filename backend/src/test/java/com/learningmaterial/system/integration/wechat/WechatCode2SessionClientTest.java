package com.learningmaterial.system.integration.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.WechatIdentity;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WechatCode2SessionClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> query = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService executor;

    @AfterEach void close() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test void exchangesEncodedCodeForOnlyTheServerProvidedIdentity() throws Exception {
        var client = client("{\"openid\":\"wx-open-id\",\"session_key\":\"private-session\",\"unionid\":\"private-union\"}",
            Duration.ofSeconds(2), 0);
        assertThat(client.exchange("fresh+code &")).isEqualTo(new WechatIdentity("test-app", "wx-open-id"));
        assertThat(query.get()).isEqualTo("appid=test-app&secret=test%2Bsecret&js_code=fresh%2Bcode+%26&grant_type=authorization_code");
        assertThat(requests).hasValue(1);
    }

    @Test void disabledOrMissingCredentialsFailWithoutSendingACode() {
        for (var client : new WechatCode2SessionClient[]{
            new WechatCode2SessionClient(false, "test-app", "test-secret", json),
            new WechatCode2SessionClient(true, "", "test-secret", json),
            new WechatCode2SessionClient(true, "test-app", "", json)}) {
            assertThatThrownBy(() -> client.exchange("fresh-code")).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status().value()).isEqualTo(503);
                assertThat(error.code()).isEqualTo("WECHAT_NOT_CONFIGURED");
            });
        }
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"40029,422,WECHAT_CODE_INVALID", "40163,422,WECHAT_CODE_INVALID",
        "40125,503,WECHAT_AUTH_FAILED", "-1,503,WECHAT_PROVIDER_UNAVAILABLE", "45011,429,WECHAT_RATE_LIMITED"})
    void mapsWechatErrorsWithoutRetryingOrExposingProviderDetails(int code, int status, String expected) throws Exception {
        var client = client("{\"errcode\":" + code + ",\"errmsg\":\"private-provider-details\"}", Duration.ofSeconds(2), 0);
        assertThatThrownBy(() -> client.exchange("fresh-code")).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status().value()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(expected);
            assertThat(error.toString()).doesNotContain("private-provider-details", "test+secret", "fresh-code");
            assertThat(error.getCause()).isNull();
        });
        assertThat(requests).hasValue(1);
    }

    @Test void refusesAResponseWithoutAStringOpenId() throws Exception {
        var client = client("{\"openid\":123,\"session_key\":\"private-session\"}", Duration.ofSeconds(2), 0);
        assertThatThrownBy(() -> client.exchange("fresh-code")).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.code()).isEqualTo("WECHAT_INVALID_RESPONSE"));
    }

    @Test void timeoutIncludesAStalledResponseBody() throws Exception {
        var client = client("{\"openid\":\"wx-open-id\"}", Duration.ofMillis(100), 3000);
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.exchange("fresh-code")).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.code()).isEqualTo("WECHAT_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    private WechatCode2SessionClient client(String body, Duration timeout, long bodyDelay) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, bytes.length);
                if (bodyDelay > 0) Thread.sleep(bodyDelay);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return new WechatCode2SessionClient(true, "test-app", "test+secret", json,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), timeout);
    }
}
