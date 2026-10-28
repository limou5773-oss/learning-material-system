package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.mapper.StatisticsRepository;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class StatisticsIntegrationTest {
    static final String RANGE =
            "from=2040-01-01T16:00:00Z&to=2040-01-04T16:00:00Z&timezone=Asia%2FShanghai";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean StatisticsRepository repository;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<Long> users = new ArrayList<>();
    String admin, ordinary;
    long owner;

    @BeforeEach
    void setup() throws Exception {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        ordinary = account(false);
        owner = users.get(0);
        admin = account(true);
    }

    @AfterEach
    void clean() {
        reset(repository);
        for (long user : users) {
            for (String table :
                    List.of("ai_result", "ai_tool_call", "ocr_attempt", "model_attempt"))
                jdbc.update(
                        "DELETE FROM "
                                + table
                                + " WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)",
                        user);
            jdbc.update("DELETE FROM ai_task WHERE user_id=?", user);
            jdbc.update("DELETE FROM material WHERE user_id=?", user);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", user);
            jdbc.update("DELETE FROM app_user WHERE id=?", user);
        }
    }

    @Test
    void emptyPeriodReturnsDenseDaysFixedStatusesAndNoInventedCosts() throws Exception {
        Reply response = get(RANGE, admin);
        assertThat(response.status()).isEqualTo(200);
        JsonNode data = response.data();
        assertThat(data.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "from",
                        "to",
                        "timezone",
                        "activeMaterialCount",
                        "restrictedMaterialCount",
                        "subjects",
                        "uploadTrend",
                        "modelAttemptCount",
                        "ocrAttemptCount",
                        "retryAttemptCount",
                        "unknownRetryAttempts",
                        "taskStatuses",
                        "taskCompletionRate",
                        "candidateTaskCount",
                        "knownCosts",
                        "unknownCostAttempts",
                        "metricNotes");
        assertThat(data.path("from").asText()).isEqualTo("2040-01-01T16:00:00Z");
        assertThat(data.path("to").asText()).isEqualTo("2040-01-04T16:00:00Z");
        assertThat(data.path("timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(data.path("uploadTrend").findValuesAsText("date"))
                .containsExactly("2040-01-02", "2040-01-03", "2040-01-04");
        assertThat(data.path("uploadTrend").findValuesAsText("count")).containsOnly("0");
        assertThat(data.path("taskStatuses").findValuesAsText("key"))
                .containsExactly(
                        "QUEUED", "RUNNING", "WAIT_CONFIRM", "SUCCEEDED", "FAILED", "CANCELLED");
        assertThat(data.path("taskCompletionRate").isNull()).isTrue();
        assertThat(data.path("knownCosts").isEmpty()).isTrue();
        for (String field :
                List.of(
                        "modelAttemptCount",
                        "ocrAttemptCount",
                        "retryAttemptCount",
                        "unknownRetryAttempts",
                        "candidateTaskCount",
                        "unknownCostAttempts"))
            assertThat(data.path(field).asLong()).as(field).isZero();
        assertThat(data.path("metricNotes").toString())
                .contains("MOCK", "UNKNOWN", "币种", "历史", "SUCCEEDED", "FAILED");
    }

    @Test
    void permissionsAndDateErrorsAreExplicit() throws Exception {
        assertThat(get(RANGE, null).status()).isEqualTo(401);
        assertThat(get(RANGE, ordinary).status()).isEqualTo(403);
        assertThat(get(RANGE.replace("&timezone=Asia%2FShanghai", ""), admin).status())
                .isEqualTo(400);
        for (String query :
                List.of(
                        "",
                        "from=2040-01-01T16:00:00Z",
                        RANGE.replace("2040-01-01T16:00:00Z", "2040-01-01"),
                        RANGE.replace("2040-01-01T16:00:00Z", "2040-01-02T00:00:00%2B08:00"),
                        RANGE.replace("2040-01-01T16:00:00Z", "2040-02-30T16:00:00Z")))
            assertThat(get(query, admin).status()).as(query).isEqualTo(400);
        for (String query :
                List.of(
                        RANGE.replace("Asia%2FShanghai", "UTC"),
                        RANGE.replace("2040-01-01T16:00:00Z", "2040-01-01T16:00:01Z"),
                        RANGE.replace("2040-01-01T16:00:00Z", "2040-01-04T16:00:00Z"),
                        RANGE.replace("2040-01-01T16:00:00Z", "2040-01-05T16:00:00Z"),
                        RANGE.replace("2040-01-04T16:00:00Z", "2040-04-02T16:00:00Z")))
            assertThat(get(query, admin).status()).as(query).isEqualTo(422);
    }

    @Test
    void historicShanghaiSummerDayUsesItsActualUtcBoundaries() throws Exception {
        // In July 1991 Shanghai used UTC+9. Both events before 16:00Z belong to
        // different days from a fixed UTC+8 calculation, including the deleted upload.
        material("ACTIVE", "NORMAL", "历史时区", "1991-07-01 15:00:00");
        material("DELETED", "NORMAL", "历史时区", "1991-07-02 15:59:59.999999");
        material("ACTIVE", "NORMAL", "历史时区", "1991-07-03 15:00:00");
        Reply response =
                get(
                        "from=1991-07-01T15:00:00Z&to=1991-07-03T15:00:00Z&timezone=Asia%2FShanghai",
                        admin);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data().path("uploadTrend").findValuesAsText("date"))
                .containsExactly("1991-07-02", "1991-07-03");
        assertThat(response.data().path("uploadTrend").findValuesAsText("count"))
                .containsExactly("1", "1");
    }

    @Test
    void historicShanghaiClockChangeIncludesTheEntireTwentyFiveHourDay() throws Exception {
        material("ACTIVE", "NORMAL", "历史时区", "1991-09-14 15:00:00");
        material("DELETED", "NORMAL", "历史时区", "1991-09-15 15:30:00");
        material("ACTIVE", "NORMAL", "历史时区", "1991-09-15 16:00:00");
        Reply response =
                get(
                        "from=1991-09-14T15:00:00Z&to=1991-09-15T16:00:00Z&timezone=Asia%2FShanghai",
                        admin);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data().path("uploadTrend").findValuesAsText("date"))
                .containsExactly("1991-09-15");
        assertThat(response.data().path("uploadTrend").findValuesAsText("count"))
                .containsExactly("2");
    }

    @Test
    void ninetyCalendarDaysRemainAllowedAcrossHistoricClockChange() throws Exception {
        Reply response =
                get(
                        "from=1991-07-01T15:00:00Z&to=1991-09-29T16:00:00Z&timezone=Asia%2FShanghai",
                        admin);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data().path("uploadTrend").size()).isEqualTo(90);
    }

    @Test
    void currentInventoryIncludesArchivedRestrictedAndAllSubjectsWhileUploadEventsIncludeDeleted()
            throws Exception {
        long activeBefore =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM material WHERE status<>'DELETED'", Long.class);
        long restrictedBefore =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM material WHERE status<>'DELETED' AND"
                                + " access_state='RESTRICTED'",
                        Long.class);
        material("ACTIVE", "NORMAL", "数学", "2040-01-01 15:59:59.999999");
        material("ARCHIVED", "RESTRICTED", " 数学 ", "2040-01-01 16:00:00");
        material("DELETED", "RESTRICTED", "删除学科", "2040-01-02 15:59:59.999999");
        material("ACTIVE", "NORMAL", null, "2040-01-02 16:00:00");
        material("ACTIVE", "NORMAL", "   ", "2040-01-04 16:00:00");
        material("ACTIVE", "NORMAL", "语文", "2039-01-01 00:00:00");
        JsonNode data = get(RANGE, admin).data();
        assertThat(data.path("activeMaterialCount").asLong()).isEqualTo(activeBefore + 5);
        assertThat(data.path("restrictedMaterialCount").asLong()).isEqualTo(restrictedBefore + 1);
        assertThat(data.path("uploadTrend").findValuesAsText("count"))
                .containsExactly("2", "1", "0");
        Map<String, Long> subjects = buckets(data.path("subjects"));
        assertThat(subjects)
                .containsEntry("数学", 2L)
                .containsEntry("未分类", 2L)
                .containsEntry("语文", 1L)
                .doesNotContainKey("删除学科");
        JsonNode another =
                get(
                                RANGE.replace("2040-01-01T16:00:00Z", "2050-01-01T16:00:00Z")
                                        .replace("2040-01-04T16:00:00Z", "2050-01-04T16:00:00Z"),
                                admin)
                        .data();
        assertThat(another.path("subjects")).isEqualTo(data.path("subjects"));
        assertThat(another.path("activeMaterialCount")).isEqualTo(data.path("activeMaterialCount"));
        assertThat(data.toString())
                .doesNotContain(
                        "private fixture body",
                        "private fixture title",
                        "userId",
                        "materialId",
                        "taskId",
                        "providerKey");
    }

    @Test
    void taskCreationRangeAndEverValidCandidatesIncludeInvalidatedMockButNotInvalidOrOutside()
            throws Exception {
        long m = material("ACTIVE", "NORMAL", "历史", "2039-01-01 00:00:00");
        for (String status :
                List.of("QUEUED", "RUNNING", "WAIT_CONFIRM", "SUCCEEDED", "FAILED", "CANCELLED")) {
            long task = task(m, status, "2040-01-01 16:00:00");
            if (status.equals("WAIT_CONFIRM")) result(task, "CANDIDATE", "{\"valid\":true}");
            if (status.equals("SUCCEEDED")) result(task, "CONFIRMED", "{\"valid\":true}");
            if (status.equals("CANCELLED")) result(task, "INVALIDATED", "{\"valid\":true}");
            if (status.equals("FAILED")) result(task, "INVALIDATED", "{\"valid\":false}");
        }
        result(task(m, "FAILED", "2040-01-03 16:00:00"), "INVALIDATED", "{\"valid\":\"true\"}");
        result(task(m, "SUCCEEDED", "2040-01-04 16:00:00"), "CANDIDATE", "{\"valid\":true}");
        JsonNode data = get(RANGE, admin).data();
        assertThat(buckets(data.path("taskStatuses")))
                .containsEntry("QUEUED", 1L)
                .containsEntry("RUNNING", 1L)
                .containsEntry("WAIT_CONFIRM", 1L)
                .containsEntry("SUCCEEDED", 1L)
                .containsEntry("FAILED", 2L)
                .containsEntry("CANCELLED", 1L);
        assertThat(data.path("taskCompletionRate").asDouble())
                .isCloseTo(1.0 / 3, within(0.00000001));
        assertThat(data.path("candidateTaskCount").asLong()).isEqualTo(3);
    }

    @Test
    void sentAttemptDatesStepsAndCurrenciesHaveIndependentNullSafeAccounting() throws Exception {
        long m = material("ACTIVE", "NORMAL", null, "2039-01-01 00:00:00"),
                t = task(m, "FAILED", "2039-01-01 00:00:00");
        model(t, 1, 1, 1, true, "SUCCESS", "2040-01-01 16:00:00", "1.250000", "CNY");
        model(t, 2, 2, 1, true, "SUCCESS", "2040-01-02 16:00:00", "0", "USD");
        model(t, 3, 2, 2, true, "FAILED", "2040-01-03 16:00:00", "0.750000", "CNY");
        model(t, 4, null, null, true, "UNKNOWN", "2040-01-03 16:00:00", null, null);
        model(t, 5, 3, null, true, "RUNNING", "2040-01-03 16:00:00", "1", null);
        model(t, 6, 4, 1, false, "FAILED", "2040-01-03 16:00:00", "9", "USD");
        model(t, 7, 5, 1, true, "SUCCESS", "2040-01-04 16:00:00", "9", "USD");
        model(t, 8, 6, 1, true, "SUCCESS", "2040-01-01 15:59:59.999999", "9", "USD");
        ocr(t, 0, 1, true, "SUCCESS", "2", "USD");
        ocr(t, 0, 2, true, "UNKNOWN", "-1", "USD");
        ocr(t, 1, 1, true, "FAILED", "3", "usd");
        ocr(t, 1, 2, true, "RUNNING", "4", "???");
        ocr(t, 2, 1, true, "SUCCESS", null, "CNY");
        ocr(t, 2, 2, false, "FAILED", "5", "CNY");
        // Wrapping MODEL steps are deliberately irrelevant to external request counts and cost.
        jdbc.update(
                "INSERT INTO"
                    + " ai_tool_call(task_id,step_no,attempt_no,execution_epoch,tool_name,provider_kind,status,started_at,cost_amount,currency)"
                    + " VALUES(?,1,1,1,'planner','MODEL','SUCCESS','2040-01-02"
                    + " 16:00:00',999,'USD')",
                t);
        JsonNode data = get(RANGE, admin).data();
        assertThat(data.path("modelAttemptCount").asLong()).isEqualTo(5);
        assertThat(data.path("ocrAttemptCount").asLong()).isEqualTo(5);
        assertThat(data.path("retryAttemptCount").asLong()).isEqualTo(3);
        assertThat(data.path("unknownRetryAttempts").asLong()).isEqualTo(2);
        assertThat(data.path("unknownCostAttempts").asLong()).isEqualTo(6);
        assertThat(data.path("knownCosts").findValuesAsText("currency"))
                .containsExactly("CNY", "USD");
        assertThat(data.path("knownCosts").get(0).path("amount").asText()).isEqualTo("2.000000");
        assertThat(data.path("knownCosts").get(1).path("amount").asText()).isEqualTo("2.000000");
        assertThat(data.path("knownCosts").findValuesAsText("knownAttempts"))
                .containsExactly("2", "2");
        assertThat(data.path("taskStatuses").findValuesAsText("count")).containsOnly("0");
    }

    @Test
    void explicitZeroCostRemainsKnownWhileEmptyTaskDenominatorRemainsNull() throws Exception {
        long t =
                task(
                        material("ACTIVE", "NORMAL", null, "2039-01-01 00:00:00"),
                        "WAIT_CONFIRM",
                        "2040-01-02 16:00:00");
        model(t, 1, 1, 1, true, "SUCCESS", "2040-01-02 16:00:00", "0", "USD");
        JsonNode data = get(RANGE, admin).data();
        assertThat(data.path("knownCosts").get(0).path("amount").asText()).isEqualTo("0.000000");
        assertThat(data.path("knownCosts").get(0).path("knownAttempts").asLong()).isEqualTo(1);
        assertThat(data.path("unknownCostAttempts").asLong()).isZero();
        assertThat(data.path("taskCompletionRate").isNull()).isTrue();
    }

    @Test
    void allQueriesObserveOneReadOnlyRepeatableReadSnapshot() throws Exception {
        material("ACTIVE", "NORMAL", "快照原值", "2040-01-02 16:00:00");
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isCurrentTransactionReadOnly())
                                    .isTrue();
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .getCurrentTransactionIsolationLevel())
                                    .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                            Object inventory = invocation.callRealMethod();
                            CompletableFuture.runAsync(
                                            () ->
                                                    material(
                                                            "ACTIVE",
                                                            "RESTRICTED",
                                                            "快照新增值",
                                                            "2040-01-02 16:00:00"))
                                    .get();
                            return inventory;
                        })
                .when(repository)
                .inventory();
        Reply response = get(RANGE, admin);
        assertThat(response.status()).isEqualTo(200);
        JsonNode data = response.data();
        assertThat(buckets(data.path("subjects")))
                .doesNotContainKey("快照新增值")
                .containsEntry("快照原值", 1L);
        assertThat(
                        data.path("subjects").findValuesAsText("count").stream()
                                .mapToLong(Long::parseLong)
                                .sum())
                .isEqualTo(data.path("activeMaterialCount").asLong());
        assertThat(data.path("uploadTrend").findValuesAsText("count"))
                .containsExactly("0", "1", "0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM material WHERE subject='快照新增值'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void databaseQueryFailureReturns500WithoutEmptyOrPrivateData() throws Exception {
        doAnswer(
                        invocation ->
                                jdbc.queryForObject(
                                        "SELECT d23_missing_column FROM material", Long.class))
                .when(repository)
                .inventory();
        Reply response = get(RANGE, admin);
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().has("data")).isFalse();
        assertThat(response.body().toString())
                .doesNotContain("SQL", "d23_missing_column", "private fixture");
    }

    Map<String, Long> buckets(JsonNode list) {
        Map<String, Long> values = new LinkedHashMap<>();
        list.forEach(item -> values.put(item.path("key").asText(), item.path("count").asLong()));
        return values;
    }

    long material(String status, String access, String subject, String created) {
        jdbc.update(
                "INSERT INTO"
                    + " material(user_id,title,subject,material_type,note_text,version,status,access_state,created_at,updated_at)"
                    + " VALUES(?,'private fixture title',?,'NOTE','private fixture"
                    + " body',1,?,?,?,UTC_TIMESTAMP(6))",
                owner,
                subject,
                status,
                access,
                created);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM material WHERE user_id=?", Long.class, owner);
    }

    long task(long material, String status, String created) {
        jdbc.update(
                "INSERT INTO"
                    + " ai_task(user_id,material_id,material_version,request_key,request_hash,goal_type,status,step_count,cancel_flag,execution_epoch,config_json,created_at,updated_at)"
                    + " VALUES(?,?,1,?,REPEAT('a',64),'REVIEW_CARDS',?,0,0,1,'{}',?,UTC_TIMESTAMP(6))",
                owner,
                material,
                UUID.randomUUID().toString(),
                status,
                created);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM ai_task WHERE user_id=?", Long.class, owner);
    }

    void result(long task, String status, String validation) {
        jdbc.update(
                "INSERT INTO"
                    + " ai_result(task_id,version,candidate_json,validation_json,status,created_at,updated_at)"
                    + " VALUES(?,1,'{\"mode\":\"MOCK\"}',?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                task,
                validation,
                status);
    }

    void model(
            long task,
            int number,
            Integer step,
            Integer stepAttempt,
            boolean sent,
            String status,
            String created,
            String cost,
            String currency) {
        jdbc.update(
                "INSERT INTO"
                    + " model_attempt(task_id,execution_epoch,attempt_no,step_no,step_attempt_no,purpose,provider_key,requested_model,prompt_version,dispatched,status,started_at,cost,currency)"
                    + " VALUES(?,1,?,?,?,?,'D23_SOFTWARE_FIXTURE','fixture','fixture',?,?,?,?,?)",
                task,
                number,
                step,
                stepAttempt,
                number == 1 ? "PLANNER" : "GENERATION",
                sent,
                status,
                created,
                cost,
                currency);
    }

    void ocr(
            long task,
            int page,
            int number,
            boolean sent,
            String status,
            String cost,
            String currency) {
        jdbc.update(
                "INSERT INTO"
                    + " ocr_attempt(task_id,execution_epoch,page_no,attempt_no,provider_key,dispatched,status,started_at,cost,currency)"
                    + " VALUES(?,1,?,?,'D23_SOFTWARE_FIXTURE',?,?,'2040-01-02 16:00:00',?,?)",
                task,
                page,
                number,
                sent,
                status,
                cost,
                currency);
    }

    Reply get(String query, String token) throws Exception {
        return request("GET", "/admin/statistics?" + query, null, token);
    }

    String account(boolean isAdmin) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                username = "d23_" + suffix,
                password = "Safe_" + suffix;
        Reply registered =
                request(
                        "POST",
                        "/auth/registrations",
                        Map.of(
                                "username",
                                username,
                                "password",
                                password,
                                "displayName",
                                "D23 software fixture"),
                        null);
        assertThat(registered.status()).isEqualTo(201);
        long id = registered.data().path("id").asLong();
        users.add(id);
        if (isAdmin) jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?", id);
        return request(
                        "POST",
                        "/auth/sessions",
                        Map.of("username", username, "password", password, "clientType", "WEB"),
                        null)
                .data()
                .path("accessToken")
                .asText();
    }

    Reply request(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                        .timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        var response =
                http.send(
                        builder.method(
                                        method,
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(
                                                        json.writeValueAsString(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }

    record Reply(int status, JsonNode body) {
        JsonNode data() {
            return body.path("data");
        }
    }
}
