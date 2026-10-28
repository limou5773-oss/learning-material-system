package com.learningmaterial.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class AdminTaskIntegrationTest {
    private static final String RANGE = "from=2090-01-01T00:00:00Z&to=2090-01-04T00:00:00Z";
    private static final String PRIVATE = "F21_PRIVATE_CANARY";
    private static final String MAPPER = "com.learningmaterial.system.mapper.AdminTaskMapper.";
    private static final List<String> STATUSES = List.of("QUEUED", "RUNNING", "WAIT_CONFIRM", "SUCCEEDED", "FAILED", "CANCELLED");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean SqlSessionTemplate session;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> users = new ArrayList<>();
    private Account admin, ordinary;
    private long material;

    @BeforeEach
    void setup() throws Exception {
        ordinary = account(false);
        admin = account(true);
        material = material(ordinary.id(), "ACTIVE", "NORMAL");
    }

    @AfterEach
    void clean() {
        reset(session);
        for (long user : users) {
            jdbc.update("DELETE FROM ai_tool_call WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", user);
            jdbc.update("DELETE FROM ai_task WHERE user_id=?", user);
            jdbc.update("DELETE FROM material WHERE user_id=?", user);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", user);
            jdbc.update("DELETE FROM app_user WHERE id=?", user);
        }
    }

    @Test
    void bothAdminEndpointsRejectAnonymousAndOrdinaryAccounts() throws Exception {
        long task = task(material, "QUEUED", "2090-01-02 00:00:00");
        for (String path : List.of("/admin/ai-tasks", "/admin/ai-tasks/" + task + "/steps")) {
            assertThat(get(path, null).status()).isEqualTo(401);
            assertThat(get(path, ordinary.token()).status()).isEqualTo(403);
            assertThat(get(path, admin.token()).status()).isEqualTo(200);
        }
    }

    @Test
    void taskProjectionIsExactAndIncludesCrossAccountDeletedOrRestrictedHistory() throws Exception {
        long first = task(material, "SUCCEEDED", "2090-01-02 00:00:00");
        long deleted = material(admin.id(), "DELETED", "NORMAL");
        long second = task(deleted, "FAILED", "2090-01-02 00:00:00");
        jdbc.update("UPDATE material SET access_state='RESTRICTED' WHERE id=?", material);
        jdbc.update("UPDATE ai_task SET stage='CANDIDATE_READY',step_count=3,error_code='MODEL_TIMEOUT' WHERE id=?", first);
        Reply response = list(RANGE);
        assertThat(response.status()).isEqualTo(200);
        assertPage(response.data(), 1, 20, 2);
        assertThat(ids(response.data())).containsExactly(second, first);
        JsonNode row = response.data().path("items").get(1);
        assertThat(row.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "userId", "materialId", "goalType", "status", "stage", "stepCount", "errorCode", "createdAt", "startedAt", "finishedAt", "durationMs");
        for (String field : List.of("id", "userId", "materialId")) assertThat(row.path(field).isTextual()).as(field).isTrue();
        assertThat(row.path("userId").asText()).isEqualTo(Long.toString(ordinary.id()));
        assertThat(row.path("materialId").asText()).isEqualTo(Long.toString(material));
        assertThat(row.path("goalType").asText()).isEqualTo("REVIEW_CARDS");
        assertThat(row.path("stage").asText()).isEqualTo("CANDIDATE_READY");
        assertThat(row.path("stepCount").asInt()).isEqualTo(3);
        assertThat(row.path("errorCode").asText()).isEqualTo("MODEL_TIMEOUT");
        assertThat(row.path("createdAt").asText()).isEqualTo("2090-01-02T00:00:00Z");
        assertThat(row.path("startedAt").isNull()).isTrue();
        assertThat(row.path("finishedAt").isNull()).isTrue();
        assertThat(row.path("durationMs").isNull()).isTrue();
        assertPrivateAbsent(response);
    }

    @Test
    void stableTaskPaginationSupportsSixStatusesPageLimitsAndHighEmptyPages() throws Exception {
        List<Long> created = new ArrayList<>();
        for (String status : STATUSES) created.add(task(material, status, "2090-01-02 00:00:00"));
        Reply page = list(RANGE + "&page=2&pageSize=2");
        assertThat(page.status()).isEqualTo(200);
        assertPage(page.data(), 2, 2, 6);
        assertThat(ids(page.data())).containsExactly(created.get(3), created.get(2));
        for (int i = 0; i < STATUSES.size(); i++) {
            Reply filtered = list(RANGE + "&status=" + STATUSES.get(i));
            assertThat(filtered.status()).isEqualTo(200);
            assertPage(filtered.data(), 1, 20, 1);
            assertThat(ids(filtered.data())).containsExactly(created.get(i));
        }
        assertThat(list(RANGE + "&status=%20%20").data().path("total").asLong()).isEqualTo(6);
        assertThat(list(RANGE + "&pageSize=100").status()).isEqualTo(200);
        Reply high = list(RANGE + "&page=2147483647&pageSize=100");
        assertThat(high.status()).isEqualTo(200);
        assertPage(high.data(), Integer.MAX_VALUE, 100, 6);
        assertThat(high.data().path("items")).isEmpty();
    }

    @Test
    void createdBoundsAreOptionalAndLeftClosedRightOpenWithoutDailyOrNinetyDayRestrictions() throws Exception {
        long before = task(material, "QUEUED", "2090-01-01 23:59:59.999999");
        long lower = task(material, "RUNNING", "2090-01-02 00:00:00");
        long middle = task(material, "WAIT_CONFIRM", "2090-01-02 05:01:02.123456");
        long upper = task(material, "SUCCEEDED", "2090-01-03 00:00:00");
        assertThat(ids(list("from=2090-01-02T00:00:00Z&to=2090-01-03T00:00:00Z").data())).containsExactly(middle, lower);
        assertThat(ids(list("from=2090-01-02T05:01:02.123456Z").data())).containsExactly(upper, middle);
        assertThat(ids(list("to=2090-01-02T00:00:00Z").data())).contains(before).doesNotContain(lower, middle, upper);
        assertThat(list("from=1000-01-01T00:00:00Z&to=9999-12-31T23:59:59.999999Z").status()).isEqualTo(200);
        assertThat(list("from=2090-01-01T03:14:15Z&to=2091-01-01T01:02:03Z").status()).isEqualTo(200);
        assertThat(list("").status()).isEqualTo(200);
        assertThat(list("from=2091-01-01T00:00:00Z").data().path("items")).isEmpty();
    }

    @Test
    void formatErrorsAre400AndBusinessRangesOrEnumsAre422() throws Exception {
        for (String query : List.of("page=x", "pageSize=1.5", "page=2147483648", "from=", "from=tomorrow", "from=2090-02-30T00:00:00Z", "to=2090-01-01", "from=2090-01-01T00:00:00%2B00:00", "to=2090-01-01T00:00:00", "to=2090-01-01T00:00:00z")) {
            assertThat(list(query).status()).as(query).isEqualTo(400);
        }
        for (String query : List.of("page=0", "page=-1", "pageSize=0", "pageSize=101", "status=UNKNOWN", "status=running", "from=0999-12-31T23:59:59Z", "from=0000-01-01T00:00:00Z", "to=%2B10000-01-01T00:00:00Z", "from=2090-01-02T00:00:00Z&to=2090-01-02T00:00:00Z", "from=2090-01-03T00:00:00Z&to=2090-01-02T00:00:00Z")) {
            assertThat(list(query).status()).as(query).isEqualTo(422);
        }
    }

    @Test
    void unknownTaskMetadataNeverPassesThroughAndKnownGoalsStagesAndErrorsRemainUseful() throws Exception {
        long task = task(material, "PRIVATE_STATUS", "2090-01-02 00:00:00");
        jdbc.update("UPDATE ai_task SET goal_type=?,stage=?,error_code=? WHERE id=?", PRIVATE, PRIVATE, PRIVATE, task);
        Reply response = list(RANGE);
        JsonNode row = response.data().path("items").get(0);
        assertThat(response.status()).isEqualTo(200);
        for (String field : List.of("goalType", "status", "stage")) assertThat(row.path(field).asText()).as(field).isEqualTo("UNKNOWN");
        assertThat(row.path("errorCode").asText()).isEqualTo("UNRECOGNIZED_ERROR");
        assertPrivateAbsent(response);
        for (String goal : List.of("REVIEW_CARDS", "EXTRACT_KEY_POINTS", "LINK_NOTES")) {
            jdbc.update("UPDATE ai_task SET goal_type=? WHERE id=?", goal, task);
            assertThat(list(RANGE).data().path("items").get(0).path("goalType").asText()).isEqualTo(goal);
        }
        for (String stage : List.of("QUEUED", "STARTING", "PREPARE_SOURCE", "EXTRACT_PDF_TEXT", "RECOGNIZE_IMAGE", "SEARCH_PERSONAL_MATERIALS", "GENERATE_SUMMARY_CARDS", "VALIDATE_CANDIDATE", "PLANNER", "CANDIDATE_READY", "CONFIRMED", "FAILED", "CANCELLED", "SOURCE_DELETED", "SOURCE_RESTRICTED")) {
            jdbc.update("UPDATE ai_task SET stage=? WHERE id=?", stage, task);
            assertThat(list(RANGE).data().path("items").get(0).path("stage").asText()).isEqualTo(stage);
        }
        for (String code : List.of("TASK_TIMEOUT", "TASK_CANCELLED", "LEASE_LOST", "LEASE_RECOVERED", "SOURCE_RESTRICTED", "SOURCE_DELETED", "MODEL_CREDENTIALS_INVALID", "MODEL_QUOTA_EXCEEDED", "OCR_SERVICE_NOT_ENABLED", "OCR_REQUEST_FAILED", "AGENT_STOP_SOURCE_UNREADABLE", "AGENT_STOP_NO_USABLE_SOURCE", "AGENT_STOP_UNSUPPORTED_REQUEST")) {
            jdbc.update("UPDATE ai_task SET error_code=? WHERE id=?", code, task);
            assertThat(list(RANGE).data().path("items").get(0).path("errorCode").asText()).isEqualTo(code);
        }
        jdbc.update("UPDATE ai_task SET stage=NULL,error_code=NULL WHERE id=?", task);
        row = list(RANGE).data().path("items").get(0);
        assertThat(row.path("stage").isNull()).isTrue();
        assertThat(row.path("errorCode").isNull()).isTrue();
        jdbc.update("UPDATE ai_task SET stage='',error_code='' WHERE id=?", task);
        row = list(RANGE).data().path("items").get(0);
        assertThat(row.path("stage").asText()).isEqualTo("UNKNOWN");
        assertThat(row.path("errorCode").isNull()).isTrue();
    }

    @Test
    void taskDurationUsesOnlyCompletedNonnegativeSpanIncludingConfirmationWait() throws Exception {
        long task = task(material, "SUCCEEDED", "2090-01-02 00:00:00");
        for (Object[] sample : List.of(
                new Object[]{"2090-01-02 00:00:00.123456", "2090-01-02 00:00:01.373456", 1250L},
                new Object[]{"2090-01-02 00:00:00", "2090-01-03 00:00:00", 86_400_000L},
                new Object[]{"2090-01-02 00:00:00", "2090-01-02 00:00:00", 0L},
                new Object[]{"2090-01-02 00:00:01", "2090-01-02 00:00:00", null},
                new Object[]{"2090-01-02 00:00:00", null, null},
                new Object[]{null, "2090-01-02 00:00:00", null},
                new Object[]{null, null, null})) {
            jdbc.update("UPDATE ai_task SET started_at=?,finished_at=? WHERE id=?", sample[0], sample[1], task);
            Reply response = list(RANGE);
            assertThat(response.status()).isEqualTo(200);
            JsonNode row = response.data().path("items").get(0);
            assertNullableDuration(row, (Long) sample[2]);
            for (String field : List.of("startedAt", "finishedAt")) if (!row.path(field).isNull()) assertThat(row.path(field).asText()).endsWith("Z");
        }
    }

    @Test
    void stepsUseExactSafeProjectionAndStableStepAttemptPagination() throws Exception {
        long task = task(material, "WAIT_CONFIRM", "2090-01-02 00:00:00");
        step(task, 2, 1, "generate_summary_cards", "MODEL", "SUCCESS");
        step(task, 1, 2, "recognize_image", "OCR", "FAILED");
        step(task, 1, 1, "prepare_source", "LOCAL", "RUNNING");
        Reply response = steps(task, "page=2&pageSize=1");
        assertThat(response.status()).isEqualTo(200);
        assertPage(response.data(), 2, 1, 3);
        JsonNode row = response.data().path("items").get(0);
        assertThat(row.fieldNames()).toIterable().containsExactlyInAnyOrder("stepNo", "attemptNo", "toolName", "providerKind", "status", "errorCode", "startedAt", "finishedAt", "durationMs");
        assertThat(row.path("stepNo").asInt()).isEqualTo(1);
        assertThat(row.path("attemptNo").asInt()).isEqualTo(2);
        assertThat(row.path("toolName").asText()).isEqualTo("recognize_image");
        assertThat(row.path("providerKind").asText()).isEqualTo("OCR");
        assertThat(row.path("status").asText()).isEqualTo("FAILED");
        assertThat(row.path("startedAt").asText()).isEqualTo("2090-01-02T00:00:00.123456Z");
        assertThat(row.path("finishedAt").isNull()).isTrue();
        assertThat(row.path("errorCode").isNull()).isTrue();
        assertThat(row.path("durationMs").isNull()).isTrue();
        assertPrivateAbsent(response);
        assertThat(steps(task, "pageSize=100").data().path("items").findValuesAsText("status")).containsExactly("RUNNING", "FAILED", "SUCCESS");
        assertThat(steps(task, "page=2147483647&pageSize=100").data().path("items")).isEmpty();
        for (String query : List.of("page=0", "pageSize=0", "pageSize=101")) assertThat(steps(task, query).status()).as(query).isEqualTo(422);
        for (String query : List.of("page=x", "pageSize=1.5")) assertThat(steps(task, query).status()).as(query).isEqualTo(400);
    }

    @Test
    void toolMetadataHasControlledAllowlistsAndDurationNullSemantics() throws Exception {
        long task = task(material, "RUNNING", "2090-01-02 00:00:00");
        step(task, 1, 1, PRIVATE, "PRIVATE_KIND", "PRIVATE_STATUS");
        jdbc.update("UPDATE ai_tool_call SET error_code=? WHERE task_id=?", PRIVATE, task);
        Reply response = steps(task, "");
        assertThat(response.status()).isEqualTo(200);
        JsonNode row = response.data().path("items").get(0);
        for (String field : List.of("toolName", "providerKind", "status")) assertThat(row.path(field).asText()).as(field).isEqualTo("UNKNOWN");
        assertThat(row.path("errorCode").asText()).isEqualTo("UNRECOGNIZED_ERROR");
        assertPrivateAbsent(response);
        for (String tool : List.of("prepare_source", "extract_pdf_text", "recognize_image", "search_personal_materials", "generate_summary_cards", "validate_candidate", "planner")) {
            jdbc.update("UPDATE ai_tool_call SET tool_name=? WHERE task_id=?", tool, task);
            assertThat(steps(task, "").data().path("items").get(0).path("toolName").asText()).isEqualTo(tool);
        }
        for (String kind : List.of("LOCAL", "MODEL", "OCR")) {
            jdbc.update("UPDATE ai_tool_call SET provider_kind=? WHERE task_id=?", kind, task);
            assertThat(steps(task, "").data().path("items").get(0).path("providerKind").asText()).isEqualTo(kind);
        }
        for (String status : List.of("RUNNING", "SUCCESS", "FAILED", "UNKNOWN")) {
            jdbc.update("UPDATE ai_tool_call SET status=? WHERE task_id=?", status, task);
            assertThat(steps(task, "").data().path("items").get(0).path("status").asText()).isEqualTo(status);
        }
        jdbc.update("UPDATE ai_tool_call SET error_code='MODEL_TIMEOUT',finished_at='2090-01-02 00:00:01.373456' WHERE task_id=?", task);
        row = steps(task, "").data().path("items").get(0);
        assertThat(row.path("errorCode").asText()).isEqualTo("MODEL_TIMEOUT");
        assertNullableDuration(row, 1250L);
        assertThat(row.path("finishedAt").asText()).isEqualTo("2090-01-02T00:00:01.373456Z");
        jdbc.update("UPDATE ai_tool_call SET finished_at=started_at WHERE task_id=?", task);
        assertNullableDuration(steps(task, "").data().path("items").get(0), 0L);
        jdbc.update("UPDATE ai_tool_call SET finished_at='2090-01-01 00:00:00' WHERE task_id=?", task);
        assertNullableDuration(steps(task, "").data().path("items").get(0), null);
    }

    @Test
    void missingParentsAndMalformedIdsAre404WhileExistingEmptyStepsAre200AndOwnerRoutesStayPrivate() throws Exception {
        long task = task(material, "QUEUED", "2090-01-02 00:00:00");
        Reply empty = steps(task, "");
        assertThat(empty.status()).isEqualTo(200);
        assertPage(empty.data(), 1, 20, 0);
        assertThat(empty.data().path("items")).isEmpty();
        for (String id : List.of("0", "-1", "abc", "999999999999999999999999", Long.toString(Long.MAX_VALUE), "0" + task, "%2B" + task)) {
            Reply missing = get("/admin/ai-tasks/" + id + "/steps", admin.token());
            assertThat(missing.status()).as(id).isEqualTo(404);
            assertThat(missing.body().path("error").path("code").asText()).isEqualTo("TASK_NOT_FOUND");
        }
        for (String suffix : List.of("", "/steps", "/result", "/sources")) assertThat(get("/ai-tasks/" + task + suffix, admin.token()).status()).as(suffix).isEqualTo(404);
    }

    @Test
    void listCountAndRowsShareAReadOnlyRepeatableReadSnapshot() throws Exception {
        task(material, "RUNNING", "2090-01-02 00:00:00");
        doAnswer(invocation -> {
            assertSnapshot();
            Object count = invocation.callRealMethod();
            CompletableFuture.runAsync(() -> task(material, "QUEUED", "2090-01-02 00:00:00")).get();
            return count;
        }).when(session).selectOne(eq(MAPPER + "count"), any());
        Reply response = list(RANGE);
        assertThat(response.status()).isEqualTo(200);
        assertPage(response.data(), 1, 20, 1);
        assertThat(response.data().path("items").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE user_id=?", Long.class, ordinary.id())).isEqualTo(2);
    }

    @Test
    void stepParentCountAndRowsShareAReadOnlyRepeatableReadSnapshot() throws Exception {
        long task = task(material, "RUNNING", "2090-01-02 00:00:00");
        step(task, 1, 1, "prepare_source", "LOCAL", "SUCCESS");
        doAnswer(invocation -> {
            assertSnapshot();
            Object exists = invocation.callRealMethod();
            CompletableFuture.runAsync(() -> step(task, 2, 1, "planner", "MODEL", "RUNNING")).get();
            return exists;
        }).when(session).selectOne(eq(MAPPER + "exists"), any());
        Reply response = steps(task, "");
        assertThat(response.status()).isEqualTo(200);
        assertPage(response.data(), 1, 20, 1);
        assertThat(response.data().path("items").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_tool_call WHERE task_id=?", Long.class, task)).isEqualTo(2);
    }

    @Test
    void databaseFailuresReturn500WithoutFakeEmptySuccessOrPrivateSql() throws Exception {
        long task = task(material, "RUNNING", "2090-01-02 00:00:00");
        assertThat(list(RANGE).status()).isEqualTo(200);
        assertThat(steps(task, "").status()).isEqualTo(200);
        for (String operation : List.of("count", "exists", "countSteps")) {
            doAnswer(invocation -> jdbc.queryForObject("SELECT f21_private_missing_column FROM ai_task", Long.class))
                .when(session).selectOne(eq(MAPPER + operation), any());
            Reply response = operation.equals("count") ? list(RANGE) : steps(task, "");
            assertThat(response.status()).as(operation).isEqualTo(500);
            assertThat(response.body().has("data")).isFalse();
            assertThat(response.body().path("error").path("code").asText()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.body().toString()).doesNotContain("SQL", "f21_private_missing_column", PRIVATE);
            reset(session);
        }
    }

    private static void assertSnapshot() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
    }

    private static void assertNullableDuration(JsonNode row, Long expected) {
        if (expected == null) assertThat(row.path("durationMs").isNull()).isTrue();
        else assertThat(row.path("durationMs").asLong()).isEqualTo(expected);
    }

    private static void assertPage(JsonNode page, int number, int size, long total) {
        assertThat(page.fieldNames()).toIterable().containsExactlyInAnyOrder("items", "page", "pageSize", "total");
        assertThat(page.path("page").asInt()).isEqualTo(number);
        assertThat(page.path("pageSize").asInt()).isEqualTo(size);
        assertThat(page.path("total").asLong()).isEqualTo(total);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    private static void assertPrivateAbsent(Reply reply) {
        assertThat(reply.body().toString()).doesNotContain(PRIVATE, "PRIVATE_STATUS", "PRIVATE_KIND", "instruction", "config", "context", "title", "username", "outputSummary", "inputDigest", "usage", "requestKey", "requestHash", "candidate", "noteText", "storageKey");
    }

    private long material(long owner, String status, String access) {
        jdbc.update("INSERT INTO material(user_id,title,material_type,note_text,summary,storage_key,version,status,access_state,created_at,updated_at) VALUES(?,?,'NOTE',?,?,?,1,?,?,'2090-01-01 00:00:00','2090-01-01 00:00:00')", owner, PRIVATE, PRIVATE, PRIVATE, PRIVATE, status, access);
        return jdbc.queryForObject("SELECT MAX(id) FROM material WHERE user_id=?", Long.class, owner);
    }

    private long task(long source, String status, String created) {
        long owner = jdbc.queryForObject("SELECT user_id FROM material WHERE id=?", Long.class, source);
        jdbc.update("INSERT INTO ai_task(user_id,material_id,material_version,request_key,request_hash,goal_type,instruction,status,step_count,cancel_flag,execution_epoch,context_json,config_json,created_at,updated_at) VALUES(?,?,1,?,REPEAT('a',64),'REVIEW_CARDS',?,?,0,0,1,?,?,?,?)", owner, source, UUID.randomUUID().toString(), PRIVATE, status, "{\"private\":\"" + PRIVATE + "\"}", "{\"private\":\"" + PRIVATE + "\"}", created, created);
        return jdbc.queryForObject("SELECT MAX(id) FROM ai_task WHERE user_id=?", Long.class, owner);
    }

    private void step(long task, int number, int attempt, String tool, String kind, String status) {
        jdbc.update("INSERT INTO ai_tool_call(task_id,step_no,attempt_no,execution_epoch,tool_name,provider_kind,input_digest,output_summary,status,started_at,usage_json) VALUES(?,?,?,1,?,?,REPEAT('b',64),?,?,'2090-01-02 00:00:00.123456',?)", task, number, attempt, tool, kind, PRIVATE, status, "{\"private\":\"" + PRIVATE + "\"}");
    }

    private Account account(boolean isAdmin) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "f21_" + suffix, password = "Safe_" + suffix;
        Reply registered = request("POST", "/auth/registrations", Map.of("username", username, "password", password, "displayName", PRIVATE), null);
        assertThat(registered.status()).isEqualTo(201);
        long id = registered.data().path("id").asLong();
        users.add(id);
        if (isAdmin) jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?", id);
        Reply login = request("POST", "/auth/sessions", Map.of("username", username, "password", password, "clientType", "WEB"), null);
        assertThat(login.status()).isEqualTo(201);
        return new Account(id, login.data().path("accessToken").asText());
    }

    private Reply list(String query) throws Exception { return get("/admin/ai-tasks?" + query, admin.token()); }
    private Reply steps(long id, String query) throws Exception { return get("/admin/ai-tasks/" + id + "/steps?" + query, admin.token()); }
    private Reply get(String path, String token) throws Exception { return request("GET", path, null, token); }

    private Reply request(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        HttpResponse<String> response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }

    private record Account(long id, String token) { }
    private record Reply(int status, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
