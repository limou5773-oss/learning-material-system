package com.learningmaterial.system.model;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.auth.AuthDtos.RegistrationInput;
import com.learningmaterial.system.auth.AuthService;
import com.learningmaterial.system.task.*;
import com.learningmaterial.system.task.AiTaskDtos.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Real loopback HTTP transport and database reservations; no external provider is used. */
@SpringBootTest(
        properties = {
            "lms.task.processor.mode=agent",
            "lms.model.provider=deepseek",
            "lms.model.name=deepseek-flash"
        })
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class ModelAttemptProvenanceIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicInteger planners = new AtomicInteger(), generations = new AtomicInteger();
    static final List<String> systems = Collections.synchronizedList(new ArrayList<>());
    static HttpServer server;
    static URI endpoint;
    static volatile String scenario;

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        ModelClient loopbackModel(ObjectMapper json) {
            return new DeepSeekModelClient(
                    "test-only-d23-key", json, endpoint, Duration.ofSeconds(5));
        }
    }

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(
                "/chat/completions",
                exchange -> {
                    try {
                        JsonNode input = JSON.readTree(exchange.getRequestBody().readAllBytes());
                        String system = input.path("messages").get(0).path("content").asText();
                        systems.add(system);
                        String prompt = input.path("messages").get(1).path("content").asText();
                        String content;
                        int status = 200;
                        if (system.contains("actions")) {
                            int n = planners.incrementAndGet();
                            content =
                                    "MULTISTEP".equals(scenario)
                                            ? (n == 1
                                                    ? plan("prepare_source")
                                                    : plan(
                                                            "generate_summary_cards",
                                                            "validate_candidate"))
                                            : plan(
                                                    "prepare_source",
                                                    "generate_summary_cards",
                                                    "validate_candidate");
                        } else {
                            int n = generations.incrementAndGet();
                            if (n == 1 && "TRANSIENT".equals(scenario)) {
                                status = 503;
                                content = "software transient fixture";
                            } else
                                content =
                                        n == 1 && "FORMAT".equals(scenario) ? "{}" : valid(prompt);
                        }
                        var root =
                                JSON.createObjectNode()
                                        .put("id", "software-d23-request")
                                        .put("model", "deepseek-flash");
                        root.putArray("choices")
                                .addObject()
                                .put("finish_reason", "stop")
                                .putObject("message")
                                .put("role", "assistant")
                                .put("content", content);
                        root.putObject("usage")
                                .put("prompt_tokens", 11)
                                .put("completion_tokens", 22);
                        byte[] bytes = JSON.writeValueAsBytes(root);
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        endpoint =
                URI.create(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired AiTaskService tasks;
    @Autowired TaskLeaseService leases;
    @Autowired TaskProcessor processor;
    long user;

    @BeforeEach
    void setup() {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        String name = "d23_model_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user =
                Long.parseLong(
                        auth.register(new RegistrationInput(name, "Test-password-123", name)).id());
        planners.set(0);
        generations.set(0);
        systems.clear();
        scenario = "MULTISTEP";
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification WHERE user_id=?", user);
        for (String table : List.of("ai_result", "ai_tool_call", "model_attempt", "ocr_attempt"))
            jdbc.update(
                    "DELETE FROM "
                            + table
                            + " WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)",
                    user);
        jdbc.update("DELETE FROM ai_task WHERE user_id=?", user);
        jdbc.update(
                "DELETE FROM material_chunk WHERE material_id IN (SELECT id FROM material WHERE"
                    + " user_id=?)",
                user);
        jdbc.update("DELETE FROM material WHERE user_id=?", user);
        jdbc.update("DELETE FROM app_user WHERE id=?", user);
    }

    @Test
    void subsequentPlannerAndFirstGenerationAreNewStepsRatherThanRetries() {
        var lease = start();
        processor.execute(lease);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ai_task WHERE id=?",
                                String.class,
                                lease.taskId()))
                .isEqualTo("WAIT_CONFIRM");
        assertThat(planners.get()).isEqualTo(2);
        assertThat(generations.get()).isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT purpose FROM model_attempt WHERE task_id=? ORDER BY"
                                    + " attempt_no",
                                String.class,
                                lease.taskId()))
                .containsExactly("PLANNER", "PLANNER", "GENERATION");
        assertThat(
                        jdbc.queryForList(
                                "SELECT step_no FROM model_attempt WHERE task_id=? ORDER BY"
                                    + " attempt_no",
                                Integer.class,
                                lease.taskId()))
                .containsExactly(1, 3, 4);
        assertThat(
                        jdbc.queryForList(
                                "SELECT step_attempt_no FROM model_attempt WHERE task_id=? ORDER BY"
                                    + " attempt_no",
                                Integer.class,
                                lease.taskId()))
                .containsExactly(1, 1, 1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT attempt_no FROM model_attempt WHERE task_id=? ORDER BY"
                                    + " attempt_no",
                                Integer.class,
                                lease.taskId()))
                .containsExactly(1, 2, 3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM model_attempt WHERE task_id=? AND (cost IS"
                                    + " NOT NULL OR currency IS NOT NULL)",
                                Integer.class,
                                lease.taskId()))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FORMAT", "TRANSIENT"})
    void repairAndRecoverableHttpFailureIncrementOnlySameStepAttempts(String kind) {
        scenario = kind;
        var lease = start();
        processor.execute(lease);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ai_task WHERE id=?",
                                String.class,
                                lease.taskId()))
                .isEqualTo("WAIT_CONFIRM");
        assertThat(planners.get()).isEqualTo(1);
        assertThat(generations.get()).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT step_no FROM model_attempt WHERE task_id=? ORDER BY"
                                    + " attempt_no",
                                Integer.class,
                                lease.taskId()))
                .containsExactly(1, 3, 3);
        assertThat(
                        jdbc.queryForList(
                                "SELECT step_attempt_no FROM model_attempt WHERE task_id=? ORDER BY"
                                    + " attempt_no",
                                Integer.class,
                                lease.taskId()))
                .containsExactly(1, 1, 2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM model_attempt WHERE task_id=? ORDER BY"
                                    + " attempt_no",
                                String.class,
                                lease.taskId()))
                .containsExactly("SUCCESS", "FAILED", "SUCCESS");
        assertThat(systems.get(2).contains("Previous output was invalid"))
                .isEqualTo("FORMAT".equals(kind));
    }

    TaskLeaseService.Lease start() {
        jdbc.update(
                "INSERT INTO"
                    + " material(user_id,title,material_type,note_text,version,status,access_state,created_at,updated_at)"
                    + " VALUES(?,'软件测试','NOTE','准确原文知识片段',1,'ACTIVE','NORMAL',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                user);
        long material =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM material WHERE user_id=?", Long.class, user);
        var task =
                tasks.submit(
                        user,
                        new TaskInput(
                                Long.toString(material),
                                1,
                                UUID.randomUUID().toString(),
                                GoalType.REVIEW_CARDS,
                                null,
                                null,
                                false,
                                true));
        var lease = leases.claim();
        assertThat(lease.taskId()).isEqualTo(Long.parseLong(task.id()));
        return lease;
    }

    static String plan(String... tools) {
        var result = JSON.createObjectNode();
        var actions = result.putArray("actions");
        for (String tool : tools) actions.addObject().put("tool", tool).putObject("arguments");
        result.putNull("stopReason");
        return result.toString();
    }

    static String valid(String prompt) throws java.io.IOException {
        var source = JSON.readTree(prompt).path("sources").get(0);
        var root = JSON.createObjectNode().put("summary", "软件测试摘要");
        root.putArray("tags");
        root.putArray("relations");
        var card =
                root.putArray("cards")
                        .addObject()
                        .put("title", "知识")
                        .put("content", "待核对知识")
                        .putNull("summary")
                        .putNull("subject");
        card.putArray("tags");
        card.putArray("sources")
                .addObject()
                .put("chunkId", source.path("chunkId").asText())
                .put("quoteText", source.path("content").asText().substring(0, 4));
        return root.toString();
    }
}
