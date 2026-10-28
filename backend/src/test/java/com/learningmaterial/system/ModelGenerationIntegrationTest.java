package com.learningmaterial.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.dto.AiTaskDtos.*;
import com.learningmaterial.system.dto.TaskLease;
import com.learningmaterial.system.integration.model.ModelClient;
import com.learningmaterial.system.integration.model.ModelException;
import com.learningmaterial.system.integration.storage.FileStorage;
import com.learningmaterial.system.service.AiTaskService;
import com.learningmaterial.system.service.AuthService;
import com.learningmaterial.system.service.MaterialService;
import com.learningmaterial.system.service.TaskCandidateService;
import com.learningmaterial.system.service.TaskConfirmationService;
import com.learningmaterial.system.service.TaskLeaseService;
import com.learningmaterial.system.task.ModelTaskProcessor;
import com.learningmaterial.system.task.TaskExecutionException;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Function;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;

/** Deterministic provider stub tests business guards; no real DeepSeek request is made. */
@SpringBootTest(
        properties = {
            "lms.task.processor.mode=model",
            "lms.model.provider=deepseek",
            "lms.model.model=deepseek-flash"
        })
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class ModelGenerationIntegrationTest {
    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        StubModel stubModel(ObjectMapper json) {
            return new StubModel(json);
        }
    }

    static class StubModel implements ModelClient {
        final ObjectMapper json;
        int calls;
        String prompt;
        Function<String, Result> next;

        StubModel(ObjectMapper json) {
            this.json = json;
        }

        public String providerKey() {
            return "deepseek";
        }

        public Result generate(String model, String system, String user) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls++;
            prompt = user;
            return next.apply(user);
        }

        Result valid(String user) {
            try {
                var source = json.readTree(user).path("sources").get(0);
                String body =
                        json.writeValueAsString(
                                Map.of(
                                        "summary",
                                        "模型摘要",
                                        "tags",
                                        List.of("生物"),
                                        "cards",
                                        List.of(
                                                new LinkedHashMap<String, Object>() {
                                                    {
                                                        put("title", "模型卡片");
                                                        put("content", "光合作用知识");
                                                        put("summary", null);
                                                        put("subject", null);
                                                        put("tags", List.of());
                                                        put(
                                                                "sources",
                                                                List.of(
                                                                        Map.of(
                                                                                "chunkId",
                                                                                source.path(
                                                                                                "chunkId")
                                                                                        .asText(),
                                                                                "quoteText",
                                                                                source.path(
                                                                                                "content")
                                                                                        .asText()
                                                                                        .substring(
                                                                                                0,
                                                                                                Math
                                                                                                        .min(
                                                                                                                20,
                                                                                                                source.path(
                                                                                                                                "content")
                                                                                                                        .asText()
                                                                                                                        .length())))));
                                                    }
                                                })));
                return new Result(body, "deepseek-flash-response", "req-safe", 12L, 34L);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired com.learningmaterial.system.service.AuthService auth;
    @Autowired AiTaskService tasks;
    @Autowired TaskLeaseService leases;
    @Autowired ModelTaskProcessor processor;
    @Autowired TaskCandidateService candidates;
    @Autowired TaskConfirmationService confirmations;
    @Autowired com.learningmaterial.system.service.MaterialService materials;
    @Autowired StubModel model;
    @Autowired FileStorage storage;
    List<String> files = new ArrayList<>();
    long user;

    @BeforeEach
    void setup() {
        String name = "model_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var registered =
                auth.register(
                        new com.learningmaterial.system.dto.AuthDtos.RegistrationInput(
                                name, "Test-password-123", name));
        assertThat(registered.status()).isEqualTo("ENABLED");
        user = Long.parseLong(registered.id());
        model.calls = 0;
        model.next = model::valid;
    }

    @AfterEach
    void clean() {
        jdbc.update(
                "DELETE FROM card_source WHERE card_id IN (SELECT id FROM knowledge_card WHERE"
                    + " user_id=?)",
                user);
        jdbc.update("DELETE FROM knowledge_card WHERE user_id=?", user);
        for (String table : List.of("ai_result", "ai_tool_call", "model_attempt", "ocr_attempt"))
            jdbc.update(
                    "DELETE FROM "
                            + table
                            + " WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)",
                    user);
        jdbc.update("DELETE FROM ai_task WHERE user_id=?", user);
        for (String table :
                List.of("material_chunk", "material_tag", "ocr_page_cache", "pdf_page_cache"))
            jdbc.update(
                    "DELETE FROM "
                            + table
                            + " WHERE material_id IN (SELECT id FROM material WHERE user_id=?)",
                    user);
        jdbc.update("DELETE FROM material WHERE user_id=?", user);
        jdbc.update("DELETE FROM tag WHERE user_id=?", user);
        jdbc.update("DELETE FROM app_user WHERE id=?", user);
        files.forEach(storage::deleteQuietly);
    }

    @Test
    void registeredUserGetsCitedModelCandidateAndConfirmationPreservesMode() {
        var lease = start("光合作用将光能转化为化学能。");
        processor.execute(lease);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT"
                                    + " JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.modelConfig.provider'))"
                                    + " FROM ai_task WHERE id=?",
                                String.class,
                                lease.taskId()))
                .isEqualTo("deepseek");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT"
                                    + " JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.modelConfig.model'))"
                                    + " FROM ai_task WHERE id=?",
                                String.class,
                                lease.taskId()))
                .isEqualTo("deepseek-flash");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ai_task WHERE id=?",
                                String.class,
                                lease.taskId()))
                .isEqualTo("WAIT_CONFIRM");
        var result = candidates.getResult(user, lease.taskId());
        assertThat(result.generationMode()).isEqualTo("MODEL");
        assertThat(result.generationInfo().provider()).isEqualTo("deepseek");
        assertThat(result.generationInfo().model()).isEqualTo("deepseek-flash-response");
        assertThat(result.cards().getFirst().candidateKey()).startsWith("model-");
        assertThat(result.cards().getFirst().sources().getFirst().startOffset()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_card WHERE user_id=?",
                                Integer.class,
                                user))
                .isZero();
        var row = jdbc.queryForMap("SELECT * FROM model_attempt WHERE task_id=?", lease.taskId());
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("requested_model")).isEqualTo("deepseek-flash");
        assertThat(row.get("response_model")).isEqualTo("deepseek-flash-response");
        assertThat(row.get("input_tokens")).isEqualTo(12L);
        assertThat(row.get("cost")).isNull();
        var input =
                new ConfirmationInput(
                        1,
                        1,
                        result.summary(),
                        result.tags(),
                        TagMode.MERGE,
                        result.cards(),
                        result.relations());
        var receipt = confirmations.confirm(user, Long.toString(lease.taskId()), input);
        assertThat(confirmations.confirm(user, Long.toString(lease.taskId()), input))
                .isEqualTo(receipt);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT generation_mode FROM knowledge_card WHERE id=?",
                                String.class,
                                receipt.cardIds().getFirst()))
                .isEqualTo("MODEL");
    }

    @Test
    void noteBeyondMockLimitIsIncludedWithoutTruncation() {
        String note = "a".repeat(1600) + "最后的事实";
        var lease = start(note);
        processor.execute(lease);
        assertThat(model.prompt).contains("最后的事实");
        assertThat(candidates.getSources(user, lease.taskId()).getFirst().content())
                .isEqualTo(note);
    }

    @Test
    void pdfSendsOnlySelectedPagesAndRejectsPreviouslyExtractedUnselectedChunk() throws Exception {
        long material = pdf("Outside source", "Selected source");
        var first = startMaterial(material, new PageRange(1, 1));
        processor.execute(first);
        String outside = candidates.getSources(user, first.taskId()).getFirst().chunkId();
        var second = startMaterial(material, new PageRange(2, 2));
        model.next =
                p -> {
                    assertThat(p).contains("Selected source").doesNotContain("Outside source");
                    return new ModelClient.Result(
                            model.valid(p)
                                    .content()
                                    .replaceAll(
                                            "\"chunkId\":\"[0-9]+\"",
                                            "\"chunkId\":\"" + outside + "\""),
                            "deepseek-flash",
                            "safe",
                            1L,
                            1L);
                };
        assertThatThrownBy(() -> processor.execute(second)).hasMessage("MODEL_OUTPUT_INVALID");
        assertThat(count("ai_result", second)).isZero();
    }

    @Test
    void imageWithoutOcrCannotUseTitleAsModelSource() {
        jdbc.update(
                "INSERT INTO"
                    + " material(user_id,title,material_type,version,status,access_state,created_at,updated_at)"
                    + " VALUES(?,'Title cannot be"
                    + " content','IMAGE',1,'ACTIVE','NORMAL',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                user);
        long material =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM material WHERE user_id=?", Long.class, user);
        var lease = startMaterial(material, null);
        assertThatThrownBy(() -> processor.execute(lease)).hasMessage("MODEL_SOURCE_TEXT_REQUIRED");
        assertThat(model.calls).isZero();
    }

    @Test
    void oversizedNoteFailsBeforeAnyProviderAttempt() {
        var lease = start("a".repeat(60_001));
        assertThatThrownBy(() -> processor.execute(lease)).hasMessage("MODEL_TEXT_LIMIT_EXCEEDED");
        assertThat(model.calls).isZero();
        assertThat(count("model_attempt", lease)).isZero();
    }

    @Test
    void disabledUserBeforeExecutionNeverDispatches() {
        var lease = start("准确原文");
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?", user);
        assertThatThrownBy(() -> processor.execute(lease)).hasMessage("SOURCE_UNAVAILABLE");
        assertThat(model.calls).isZero();
        assertThat(count("model_attempt", lease)).isZero();
    }

    @Test
    void configurationDriftCannotSilentlyUseAnotherModel() {
        var lease = start("准确原文");
        jdbc.update(
                "UPDATE ai_task SET"
                    + " config_json=JSON_SET(config_json,'$.modelConfig.model','different-model')"
                    + " WHERE id=?",
                lease.taskId());
        assertThatThrownBy(() -> processor.execute(lease)).hasMessage("MODEL_CONFIG_CHANGED");
        assertThat(model.calls).isZero();
        assertThat(count("model_attempt", lease)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sources", "tags"})
    void duplicateSourcesAndNormalizedTagsAreRejectedBeforeConfirmation(String kind) {
        var lease = start("准确原文");
        model.next =
                p -> {
                    try {
                        var root =
                                (com.fasterxml.jackson.databind.node.ObjectNode)
                                        new ObjectMapper().readTree(model.valid(p).content());
                        var card = root.path("cards").get(0);
                        if ("sources".equals(kind))
                            ((com.fasterxml.jackson.databind.node.ArrayNode) card.path("sources"))
                                    .add(card.path("sources").get(0).deepCopy());
                        else
                            ((com.fasterxml.jackson.databind.node.ObjectNode) card)
                                    .putArray("tags")
                                    .add("Bio")
                                    .add(" bio ");
                        return new ModelClient.Result(
                                root.toString(), "deepseek-flash", "safe", 1L, 1L);
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                };
        assertThatThrownBy(() -> processor.execute(lease)).hasMessage("MODEL_OUTPUT_INVALID");
        assertThat(model.calls).isEqualTo(2);
        assertThat(count("ai_result", lease)).isZero();
    }

    @Test
    void requestThatWasNotDispatchedHasNoRetryAndRecordsFalse() {
        var lease = start("准确原文");
        model.next =
                p -> {
                    throw new ModelException("MODEL_NOT_CONFIGURED", false, false);
                };
        assertThatThrownBy(() -> processor.execute(lease)).hasMessage("MODEL_NOT_CONFIGURED");
        assertThat(model.calls).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT dispatched FROM model_attempt WHERE task_id=?",
                                Boolean.class,
                                lease.taskId()))
                .isFalse();
    }

    @Test
    void transientRetryAndFormatRepairShareOneBudget() {
        var lease = start("准确原文");
        model.next =
                p -> {
                    if (model.calls == 1) throw new ModelException("MODEL_TIMEOUT", true, true);
                    return new ModelClient.Result("{}", "deepseek-flash", "safe", 1L, 2L);
                };
        assertThatThrownBy(() -> processor.execute(lease)).hasMessage("MODEL_OUTPUT_INVALID");
        assertThat(model.calls).isEqualTo(2);
        assertThat(count("model_attempt", lease)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unknown",
                "duplicate",
                "trailing",
                "missing",
                "forged",
                "offset",
                "foreign",
                "nulltags"
            })
    void malformedOutputCanRepairOnlyOnce(String defect) {
        var lease = start("准确原文");
        model.next =
                p -> {
                    String body = model.valid(p).content();
                    body =
                            switch (defect) {
                                case "unknown" -> body.replaceFirst("\\{", "{\"extra\":1,");
                                case "duplicate" ->
                                        body.replaceFirst("\\{", "{\"summary\":\"duplicate\",");
                                case "trailing" -> body + " {}";
                                case "missing" -> body.replace("\"subject\":null,", "");
                                case "forged" -> body.replace("准确原文", "伪造引用");
                                case "offset" ->
                                        body.replace(
                                                "\"quoteText\":",
                                                "\"startOffset\":0,\"quoteText\":");
                                case "foreign" ->
                                        body.replaceAll(
                                                "\"chunkId\":\"[0-9]+\"",
                                                "\"chunkId\":\"999999999\"");
                                default -> body.replace("\"tags\":[]", "\"tags\":null");
                            };
                    return new ModelClient.Result(body, "deepseek-flash", "safe", 1L, 1L);
                };
        assertThatThrownBy(() -> processor.execute(lease))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("MODEL_OUTPUT_INVALID");
        assertThat(model.calls).isEqualTo(2);
        assertThat(count("ai_result", lease)).isZero();
        assertThat(count("model_attempt", lease)).isEqualTo(2);
    }

    @Test
    void invalidFirstResponseRepairsToValidCandidate() {
        var lease = start("准确原文");
        model.next =
                p ->
                        model.calls == 1
                                ? new ModelClient.Result("{}", "deepseek-flash", "one", 1L, 2L)
                                : model.valid(p);
        processor.execute(lease);
        assertThat(model.calls).isEqualTo(2);
        assertThat(candidates.getResult(user, lease.taskId()).generationMode()).isEqualTo("MODEL");
    }

    @Test
    void transientFailureRetriesAndAuthenticationDoesNot() {
        var first = start("准确原文");
        model.next =
                p -> {
                    if (model.calls == 1) throw new ModelException("MODEL_TIMEOUT", true, true);
                    return model.valid(p);
                };
        processor.execute(first);
        assertThat(model.calls).isEqualTo(2);
        var second = start("第二段原文");
        model.calls = 0;
        model.next =
                p -> {
                    throw new ModelException("MODEL_AUTHENTICATION_FAILED", false, true);
                };
        assertThatThrownBy(() -> processor.execute(second))
                .hasMessage("MODEL_AUTHENTICATION_FAILED");
        assertThat(model.calls).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "cancel",
                "delete",
                "disabled",
                "note",
                "version",
                "restricted",
                "fileHash",
                "fileSize",
                "config"
            })
    void changedSourceOrAccountNeverPublishesLateResult(String mutation) {
        var lease = start("准确原文");
        long material =
                jdbc.queryForObject(
                        "SELECT material_id FROM ai_task WHERE id=?", Long.class, lease.taskId());
        model.next =
                p -> {
                    switch (mutation) {
                        case "cancel" -> tasks.cancel(user, Long.toString(lease.taskId()));
                        case "delete" -> materials.delete(user, Long.toString(material), 1);
                        case "disabled" ->
                                jdbc.update(
                                        "UPDATE app_user SET status='DISABLED' WHERE id=?", user);
                        case "note" ->
                                jdbc.update(
                                        "UPDATE material SET note_text='changed' WHERE id=?",
                                        material);
                        case "version" ->
                                jdbc.update(
                                        "UPDATE material SET version=version+1 WHERE id=?",
                                        material);
                        case "fileHash" ->
                                jdbc.update(
                                        "UPDATE material SET file_hash='changed' WHERE id=?",
                                        material);
                        case "fileSize" ->
                                jdbc.update(
                                        "UPDATE material SET file_size=123 WHERE id=?", material);
                        case "config" ->
                                jdbc.update(
                                        "UPDATE ai_task SET instruction='changed' WHERE id=?",
                                        lease.taskId());
                        default ->
                                jdbc.update(
                                        "UPDATE material SET access_state='RESTRICTED' WHERE id=?",
                                        material);
                    }
                    return model.valid(p);
                };
        try {
            processor.execute(lease);
        } catch (TaskExecutionException expected) {
        }
        assertThat(count("ai_result", lease)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM model_attempt WHERE task_id=?",
                                String.class,
                                lease.taskId()))
                .isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT response_model FROM model_attempt WHERE task_id=?",
                                String.class,
                                lease.taskId()))
                .isEqualTo("deepseek-flash-response");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_tokens FROM model_attempt WHERE task_id=?",
                                Long.class,
                                lease.taskId()))
                .isEqualTo(12L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT output_tokens FROM model_attempt WHERE task_id=?",
                                Long.class,
                                lease.taskId()))
                .isEqualTo(34L);
    }

    @Test
    void oldEpochCannotAlterNewRequestAndBudgetSpansEpochs() {
        var old = start("准确原文");
        model.next =
                p -> {
                    jdbc.update(
                            "UPDATE ai_task SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND"
                                + " WHERE id=?",
                            old.taskId());
                    var newer = leases.claim();
                    jdbc.update(
                            "INSERT INTO"
                                + " model_attempt(task_id,execution_epoch,attempt_no,provider_key,requested_model,prompt_version,dispatched,status,started_at)"
                                + " VALUES(?,?,2,'deepseek','deepseek-flash','d15-v1',1,'RUNNING',UTC_TIMESTAMP(6))",
                            old.taskId(),
                            newer.epoch());
                    return model.valid(p);
                };
        processor.execute(old);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_tokens FROM model_attempt WHERE task_id=? AND"
                                    + " attempt_no=1",
                                Long.class,
                                old.taskId()))
                .isEqualTo(12L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_tokens FROM model_attempt WHERE task_id=? AND"
                                    + " attempt_no=2",
                                Long.class,
                                old.taskId()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM model_attempt WHERE task_id=? AND attempt_no=2",
                                String.class,
                                old.taskId()))
                .isEqualTo("RUNNING");
        var current = new TaskLease(old.taskId(), old.epoch() + 1);
        assertThatThrownBy(() -> processor.execute(current)).hasMessage("MODEL_ATTEMPT_LIMIT");
        assertThat(model.calls).isEqualTo(1);
    }

    private int count(String table, TaskLease lease) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE task_id=?",
                Integer.class,
                lease.taskId());
    }

    private TaskLease start(String note) {
        jdbc.update(
                "INSERT INTO"
                    + " material(user_id,title,material_type,note_text,version,status,access_state,created_at,updated_at)"
                    + " VALUES(?,'模型测试','NOTE',?,1,'ACTIVE','NORMAL',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                user,
                note);
        long material =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM material WHERE user_id=?", Long.class, user);
        return startMaterial(material, null);
    }

    private TaskLease startMaterial(long material, PageRange range) {
        var task =
                tasks.submit(
                        user,
                        new TaskInput(
                                Long.toString(material),
                                1,
                                UUID.randomUUID().toString(),
                                GoalType.REVIEW_CARDS,
                                null,
                                range,
                                false,
                                true));
        var lease = leases.claim();
        assertThat(lease.taskId()).isEqualTo(Long.parseLong(task.id()));
        return lease;
    }

    private long pdf(String... pages) throws Exception {
        try (PDDocument doc = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                var page = new PDPage();
                doc.addPage(page);
                try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    stream.newLineAtOffset(20, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            doc.save(out);
            String key = storage.store(out.toByteArray(), "pdf");
            files.add(key);
            jdbc.update(
                    "INSERT INTO"
                        + " material(user_id,title,material_type,storage_key,page_count,version,status,access_state,created_at,updated_at)"
                        + " VALUES(?,'PDF"
                        + " model','PDF',?,?,1,'ACTIVE','NORMAL',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    user,
                    key,
                    pages.length);
            return jdbc.queryForObject(
                    "SELECT MAX(id) FROM material WHERE user_id=?", Long.class, user);
        }
    }
}
