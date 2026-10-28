package com.learningmaterial.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.dto.AiTaskDtos.*;
import com.learningmaterial.system.dto.TaskLease;
import com.learningmaterial.system.integration.model.ModelClient;
import com.learningmaterial.system.integration.model.ModelException;
import com.learningmaterial.system.service.AiTaskService;
import com.learningmaterial.system.service.AuthService;
import com.learningmaterial.system.service.MaterialService;
import com.learningmaterial.system.service.TaskCandidateService;
import com.learningmaterial.system.service.TaskConfirmationService;
import com.learningmaterial.system.service.TaskLeaseService;
import com.learningmaterial.system.task.TaskProcessor;
import com.learningmaterial.system.task.TaskWorker;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(
        properties = {
            "lms.task.processor.mode=agent",
            "lms.model.provider=deepseek",
            "lms.model.model=deepseek-flash",
            "lms.ocr.enabled=true"
        })
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class AgentIntegrationTest {
    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        StubModel agentModel(ObjectMapper json) {
            return new StubModel(json);
        }

        @Bean
        @Primary
        StubOcr agentOcr() {
            return new StubOcr();
        }
    }

    static class StubOcr implements com.learningmaterial.system.integration.ocr.OcrClient {
        int calls;
        java.util.function.Supplier<Result> next;

        public String providerKey() {
            return "AGENT_TEST";
        }

        public Result recognize(byte[] bytes) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls++;
            return next.get();
        }
    }

    static class StubModel implements ModelClient {
        final ObjectMapper json;
        int calls;
        List<String> prompts = new ArrayList<>();
        BiFunction<String, String, String> next;

        StubModel(ObjectMapper json) {
            this.json = json;
        }

        public String providerKey() {
            return "deepseek";
        }

        public Result generate(String model, String system, String user) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls++;
            prompts.add(user);
            return new Result(next.apply(system, user), "deepseek-flash", "safe", 11L, 22L);
        }

        String valid(String user) {
            try {
                var source = json.readTree(user).path("sources").get(0);
                var card = new LinkedHashMap<String, Object>();
                card.put("title", "知识");
                card.put("content", "待核对知识");
                card.put("summary", null);
                card.put("subject", null);
                card.put("tags", List.of());
                card.put(
                        "sources",
                        List.of(
                                Map.of(
                                        "chunkId",
                                        source.path("chunkId").asText(),
                                        "quoteText",
                                        source.path("content").asText().strip().substring(0, 4))));
                return json.writeValueAsString(
                        Map.of(
                                "summary",
                                "摘要",
                                "tags",
                                List.of(),
                                "cards",
                                List.of(card),
                                "relations",
                                List.of()));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    @Autowired StubOcr ocr;
    @Autowired com.learningmaterial.system.integration.storage.FileStorage storage;
    @Autowired com.learningmaterial.system.service.MaterialService materials;
    List<String> files = new ArrayList<>();
    @Autowired JdbcTemplate jdbc;
    @Autowired com.learningmaterial.system.service.AuthService auth;
    @Autowired AiTaskService tasks;
    @Autowired TaskLeaseService leases;
    @Autowired ObjectProvider<TaskProcessor> processor;
    @Autowired TaskCandidateService candidates;
    @Autowired TaskConfirmationService confirmations;
    @Autowired StubModel model;
    long user;

    @BeforeEach
    void setup() {
        String name = "agent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var u =
                auth.register(
                        new com.learningmaterial.system.dto.AuthDtos.RegistrationInput(
                                name, "Test-password-123", name));
        ocr.calls = 0;
        ocr.next =
                () ->
                        new com.learningmaterial.system.integration.ocr.OcrClient.Result(
                                "OCR accurate source", 0.9, "ocr-safe");
        assertThat(u.status()).isEqualTo("ENABLED");
        user = Long.parseLong(u.id());
        model.calls = 0;
        model.prompts.clear();
        model.next =
                (s, p) ->
                        s.contains("actions")
                                ? plan(
                                        "prepare_source",
                                        "generate_summary_cards",
                                        "validate_candidate")
                                : model.valid(p);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM card_relation WHERE user_id=?", user);
        jdbc.update(
                "DELETE FROM card_source WHERE card_id IN (SELECT id FROM knowledge_card WHERE"
                        + " user_id=?)",
                user);
        jdbc.update("DELETE FROM knowledge_card WHERE user_id=?", user);
        for (String t : List.of("ai_result", "ai_tool_call", "model_attempt", "ocr_attempt"))
            jdbc.update(
                    "DELETE FROM "
                            + t
                            + " WHERE task_id IN(SELECT id FROM ai_task WHERE user_id=?)",
                    user);
        jdbc.update("DELETE FROM ai_task WHERE user_id=?", user);
        for (String t :
                List.of("material_chunk", "material_tag", "ocr_page_cache", "pdf_page_cache"))
            jdbc.update(
                    "DELETE FROM "
                            + t
                            + " WHERE material_id IN(SELECT id FROM material WHERE user_id=?)",
                    user);
        jdbc.update("DELETE FROM material WHERE user_id=?", user);
        jdbc.update("DELETE FROM tag WHERE user_id=?", user);
        jdbc.update("DELETE FROM app_user WHERE id=?", user);
        files.forEach(storage::deleteQuietly);
    }

    @ParameterizedTest
    @EnumSource(GoalType.class)
    void registeredUserPlansCitesAndConfirmsAllGoals(GoalType goal) {
        var lease = start(goal, false);
        execute(lease);
        assertThat(status(lease)).isEqualTo("WAIT_CONFIRM");
        assertThat(
                        jdbc.queryForList(
                                "SELECT purpose FROM model_attempt WHERE task_id=? ORDER BY"
                                        + " attempt_no",
                                String.class,
                                lease.taskId()))
                .containsExactly("PLANNER", "GENERATION");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_card WHERE user_id=?",
                                Integer.class,
                                user))
                .isZero();
        var r = candidates.getResult(user, lease.taskId());
        var receipt =
                confirmations.confirm(
                        user,
                        "" + lease.taskId(),
                        new ConfirmationInput(
                                1,
                                1,
                                r.summary(),
                                r.tags(),
                                TagMode.MERGE,
                                r.cards(),
                                r.relations()));
        assertThat(receipt.cardIds()).hasSize(1);
    }

    @Test
    void candidateRequiresSeparateValidateAction() {
        var lease = start(GoalType.REVIEW_CARDS, false);
        model.next =
                (s, p) ->
                        s.contains("actions")
                                ? (model.calls == 1
                                        ? plan("prepare_source", "generate_summary_cards")
                                        : "{\"actions\":[],\"stopReason\":\"UNSUPPORTED_REQUEST\"}")
                                : model.valid(p);
        assertThatThrownBy(() -> execute(lease)).hasMessage("AGENT_STOP_UNSUPPORTED_REQUEST");
        assertThat(count("ai_result", lease)).isZero();
    }

    @Test
    void unconsentedSearchRejected() {
        var lease = start(GoalType.LINK_NOTES, false);
        model.next =
                (s, p) ->
                        "{\"actions\":[{\"tool\":\"search_personal_materials\",\"arguments\":{\"keywords\":\"知识\",\"subject\":null,\"limit\":3}}],\"stopReason\":null}";
        assertThatThrownBy(() -> execute(lease)).hasMessage("AGENT_SEARCH_NOT_ALLOWED");
        assertThat(model.calls).isEqualTo(1);
    }

    @Test
    void illegalServerOwnedArgumentRejected() {
        var lease = start(GoalType.REVIEW_CARDS, false);
        model.next =
                (s, p) ->
                        "{\"actions\":[{\"tool\":\"prepare_source\",\"arguments\":{\"materialId\":\"42\"}}],\"stopReason\":null}";
        assertThatThrownBy(() -> execute(lease)).hasMessage("AGENT_PLAN_INVALID");
        assertThat(count("material_chunk", lease)).isZero();
    }

    @Test
    void noKeyFailsClearlyWithoutFallback() {
        var lease = start(GoalType.REVIEW_CARDS, false);
        model.next =
                (s, p) -> {
                    throw new ModelException("MODEL_NOT_CONFIGURED", false, false);
                };
        assertThatThrownBy(() -> execute(lease)).hasMessage("MODEL_NOT_CONFIGURED");
        assertThat(model.calls).isEqualTo(1);
        assertThat(count("ai_result", lease)).isZero();
    }

    @Test
    void cancelledPlannerResponseStaysUnknownAndCannotPublish() {
        var lease = start(GoalType.REVIEW_CARDS, false);
        model.next =
                (s, p) -> {
                    tasks.cancel(user, "" + lease.taskId());
                    return plan("prepare_source", "generate_summary_cards", "validate_candidate");
                };
        execute(lease);
        assertThat(status(lease)).isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM model_attempt WHERE task_id=?",
                                String.class,
                                lease.taskId()))
                .isEqualTo("UNKNOWN");
        assertThat(count("ai_result", lease)).isZero();
    }

    @Test
    void modeDriftRejected() {
        var lease = start(GoalType.REVIEW_CARDS, false);
        jdbc.update(
                "UPDATE ai_task SET config_json=JSON_SET(config_json,'$.processorMode','model')"
                        + " WHERE id=?",
                lease.taskId());
        assertThatThrownBy(() -> execute(lease)).hasMessage("PROCESSOR_MODE_CHANGED");
        assertThat(model.calls).isZero();
    }

    @Test
    void textPdfUsesSelectedRangeWithoutOcr() throws Exception {
        long m = pdf("Outside selected range", "Selected source fact");
        var lease = startMaterial(m, new PageRange(2, 2), GoalType.EXTRACT_KEY_POINTS, false);
        model.next =
                (s, p) ->
                        s.contains("actions")
                                ? plan(
                                        "extract_pdf_text",
                                        "generate_summary_cards",
                                        "validate_candidate")
                                : model.valid(p);
        execute(lease);
        var result = candidates.getResult(user, lease.taskId());
        assertThat(result.processedPages()).containsExactly(2);
        assertThat(ocr.calls).isZero();
        assertThat(String.join(" ", model.prompts)).doesNotContain("Outside selected range");
    }

    @Test
    void blankPdfFeedbackSwitchesToOcr() throws Exception {
        long m = pdf("");
        var lease = startMaterial(m, new PageRange(1, 1), GoalType.REVIEW_CARDS, false);
        model.next =
                (s, p) ->
                        s.contains("actions")
                                ? (model.calls == 1
                                        ? plan("extract_pdf_text")
                                        : plan(
                                                "recognize_image",
                                                "generate_summary_cards",
                                                "validate_candidate"))
                                : model.valid(p);
        execute(lease);
        assertThat(ocr.calls).isEqualTo(1);
        assertThat(model.prompts.get(1)).contains("PDF_OCR_REQUIRED", "需OCR页[1]");
        assertThat(candidates.getResult(user, lease.taskId()).processedPages()).containsExactly(1);
    }

    @Test
    void imageOcrFailureFeedsExplicitStop() throws Exception {
        long m = image();
        var lease = startMaterial(m, null, GoalType.REVIEW_CARDS, false);
        ocr.next =
                () -> new com.learningmaterial.system.integration.ocr.OcrClient.Result("", null, "ocr-empty");
        model.next =
                (s, p) ->
                        model.calls == 1
                                ? plan("recognize_image")
                                : "{\"actions\":[],\"stopReason\":\"NO_USABLE_SOURCE\"}";
        assertThatThrownBy(() -> execute(lease)).hasMessage("AGENT_STOP_NO_USABLE_SOURCE");
        assertThat(model.prompts.get(1)).contains("OCR_EMPTY_RESULT");
        assertThat(count("ai_result", lease)).isZero();
    }

    @Test
    void imageUsesRealOcrPreparationAndGenerates() throws Exception {
        var lease = startMaterial(image(), null, GoalType.REVIEW_CARDS, false);
        model.next =
                (s, p) ->
                        s.contains("actions")
                                ? plan(
                                        "recognize_image",
                                        "generate_summary_cards",
                                        "validate_candidate")
                                : model.valid(p);
        execute(lease);
        assertThat(ocr.calls).isEqualTo(1);
        assertThat(candidates.getSources(user, lease.taskId()).getFirst().extractionMethod())
                .isEqualTo("OCR");
    }

    @Test
    void ocrAuthenticationFailureIsNeverReplanned() throws Exception {
        var lease = startMaterial(image(), null, GoalType.REVIEW_CARDS, false);
        ocr.next =
                () -> {
                    throw new com.learningmaterial.system.integration.ocr.OcrException(
                            "OCR_AUTH_FAILED", false, true);
                };
        model.next = (s, p) -> plan("recognize_image");
        assertThatThrownBy(() -> execute(lease)).hasMessage("OCR_AUTH_FAILED");
        assertThat(model.calls).isEqualTo(1);
        assertThat(ocr.calls).isEqualTo(1);
    }

    @Test
    void plannerCannotDispatchUnknownToolOrDuplicateKeys() {
        for (String plan :
                List.of(
                        "{\"actions\":[{\"tool\":\"delete_material\",\"arguments\":{}}],\"stopReason\":null}",
                        "{\"actions\":[],\"actions\":[],\"stopReason\":\"NO_USABLE_SOURCE\"}",
                        "{\"actions\":[],\"stopReason\":\"NO_USABLE_SOURCE\"} {}")) {
            var lease = start(GoalType.REVIEW_CARDS, false);
            model.next = (s, p) -> plan;
            assertThatThrownBy(() -> execute(lease)).hasMessage("AGENT_PLAN_INVALID");
            assertThat(count("ai_result", lease)).isZero();
            leases.fail(lease, "AGENT_PLAN_INVALID");
        }
    }

    @Test
    void generationRequiresPreparedSourceAndValidationRequiresCandidate() {
        for (String tool : List.of("generate_summary_cards", "validate_candidate")) {
            var lease = start(GoalType.REVIEW_CARDS, false);
            model.next = (s, p) -> plan(tool);
            assertThatThrownBy(() -> execute(lease)).hasMessage("AGENT_ACTION_NOT_ALLOWED");
            leases.fail(lease, "AGENT_ACTION_NOT_ALLOWED");
        }
    }

    @Test
    void plannerBudgetSurvivesEpochReclaim() {
        var old = start(GoalType.REVIEW_CARDS, false);
        for (int n = 1; n <= 4; n++) seedAttempt(old, n, "PLANNER");
        jdbc.update(
                "UPDATE ai_task SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
                old.taskId());
        var lease = leases.claim();
        assertThat(lease.epoch()).isEqualTo(old.epoch() + 1);
        assertThatThrownBy(() -> execute(lease)).hasMessage("AGENT_PLANNER_LIMIT");
        assertThat(model.calls).isZero();
        assertThat(count("model_attempt", lease)).isEqualTo(4);
    }

    @Test
    void generationRepairSharesTwoAttemptBudgetAcrossEpochs() {
        var old = start(GoalType.REVIEW_CARDS, false);
        seedAttempt(old, 1, "GENERATION");
        jdbc.update(
                "UPDATE ai_task SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
                old.taskId());
        var lease = leases.claim();
        model.next =
                (s, p) ->
                        s.contains("actions")
                                ? plan("prepare_source", "generate_summary_cards")
                                : "{}";
        assertThatThrownBy(() -> execute(lease)).hasMessage("MODEL_OUTPUT_INVALID");
        assertThat(model.calls).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM model_attempt WHERE task_id=? AND"
                                        + " purpose='GENERATION'",
                                Integer.class,
                                lease.taskId()))
                .isEqualTo(2);
    }

    @Test
    void stepBudgetIncludesPlanner() {
        var lease = start(GoalType.REVIEW_CARDS, false);
        jdbc.update("UPDATE ai_task SET step_count=7 WHERE id=?", lease.taskId());
        assertThatThrownBy(() -> execute(lease)).hasMessage("STEP_LIMIT");
        assertThat(model.calls).isEqualTo(1);
        assertThat(count("material_chunk", lease)).isZero();
    }

    @Test
    void successfulGenerationCanRepairOnceAndKeepsTokens() {
        var lease = start(GoalType.REVIEW_CARDS, false);
        model.next =
                (s, p) ->
                        s.contains("actions")
                                ? plan(
                                        "prepare_source",
                                        "generate_summary_cards",
                                        "validate_candidate")
                                : model.calls == 2 ? "{}" : model.valid(p);
        execute(lease);
        assertThat(model.calls).isEqualTo(3);
        assertThat(status(lease)).isEqualTo("WAIT_CONFIRM");
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM model_attempt WHERE task_id=? ORDER BY"
                                        + " attempt_no",
                                String.class,
                                lease.taskId()))
                .containsExactly("SUCCESS", "FAILED", "SUCCESS");
    }

    @Test
    void deletedSourceDuringPlannerNeverPublishes() {
        var lease = start(GoalType.REVIEW_CARDS, false);
        long material =
                jdbc.queryForObject(
                        "SELECT material_id FROM ai_task WHERE id=?", Long.class, lease.taskId());
        model.next =
                (s, p) -> {
                    materials.delete(user, "" + material, 1);
                    return plan("prepare_source");
                };
        execute(lease);
        assertThat(status(lease)).isEqualTo("CANCELLED");
        assertThat(count("ai_result", lease)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_tokens FROM model_attempt WHERE task_id=?",
                                Long.class,
                                lease.taskId()))
                .isEqualTo(11L);
    }

    @Test
    void workerPollExecutesRegisteredUsersTask() {
        var lease = start(GoalType.REVIEW_CARDS, false);
        jdbc.update(
                "UPDATE ai_task SET"
                    + " status='QUEUED',started_at=NULL,execution_epoch=0,lease_until=NULL WHERE"
                    + " id=?",
                lease.taskId());
        var worker = new TaskWorker(leases, processor.getObject());
        try {
            worker.poll();
            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(status(lease)).isEqualTo("WAIT_CONFIRM"));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "stop");
        }
    }

    void seedAttempt(TaskLease l, int n, String purpose) {
        jdbc.update(
                "INSERT INTO"
                    + " model_attempt(task_id,execution_epoch,attempt_no,purpose,provider_key,requested_model,prompt_version,dispatched,status,started_at,finished_at)"
                    + " VALUES(?,?,?,?,'deepseek','deepseek-flash','d16-v1',1,'FAILED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                l.taskId(),
                l.epoch(),
                n,
                purpose);
    }

    long image() throws Exception {
        var image =
                new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        String key = storage.store(out.toByteArray(), "png");
        files.add(key);
        return file("IMAGE", key, null);
    }

    long pdf(String... texts) throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument();
                var out = new java.io.ByteArrayOutputStream()) {
            for (String text : texts) {
                var page = new org.apache.pdfbox.pdmodel.PDPage();
                doc.addPage(page);
                if (!text.isEmpty())
                    try (var stream =
                            new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                        stream.beginText();
                        stream.setFont(
                                new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName
                                                .HELVETICA),
                                12);
                        stream.newLineAtOffset(20, 700);
                        stream.showText(text);
                        stream.endText();
                    }
            }
            doc.save(out);
            String key = storage.store(out.toByteArray(), "pdf");
            files.add(key);
            return file("PDF", key, texts.length);
        }
    }

    long file(String type, String key, Integer pages) {
        jdbc.update(
                "INSERT INTO"
                    + " material(user_id,title,material_type,storage_key,page_count,version,status,access_state,created_at,updated_at)"
                    + " VALUES(?,'File"
                    + " test',?,?,?,1,'ACTIVE','NORMAL',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                user,
                type,
                key,
                pages);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM material WHERE user_id=?", Long.class, user);
    }

    TaskLease startMaterial(long m, PageRange range, GoalType goal, boolean related) {
        var t =
                tasks.submit(
                        user,
                        new TaskInput(
                                "" + m,
                                1,
                                UUID.randomUUID().toString(),
                                goal,
                                null,
                                range,
                                related,
                                true));
        var l = leases.claim();
        assertThat(l.taskId()).isEqualTo(Long.parseLong(t.id()));
        return l;
    }

    @Test
    void consentingSearchContextAndRelationArePublishedThenConfirmed() throws Exception {
        long target = savedTarget();
        var lease = start(GoalType.LINK_NOTES, true);
        model.calls = 0;
        model.prompts.clear();
        model.next =
                (system, p) ->
                        system.contains("actions") ? relatedPlan() : relatedResult(p, target);
        execute(lease);
        var result = candidates.getResult(user, lease.taskId());
        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().getFirst().target().cardId()).isEqualTo("" + target);
        assertThat(model.prompts.getLast()).contains("relatedCards", "待核对知识");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM card_relation WHERE user_id=?",
                                Integer.class,
                                user))
                .isZero();
        confirmations.confirm(
                user,
                "" + lease.taskId(),
                new ConfirmationInput(
                        1,
                        1,
                        result.summary(),
                        result.tags(),
                        TagMode.MERGE,
                        result.cards(),
                        result.relations()));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM card_relation WHERE user_id=?",
                                Integer.class,
                                user))
                .isEqualTo(1);
    }

    @Test
    void changedRelatedTargetCannotPublish() {
        long target = savedTarget();
        var lease = start(GoalType.LINK_NOTES, true);
        model.calls = 0;
        model.next =
                (system, p) -> {
                    if (system.contains("actions")) return relatedPlan();
                    jdbc.update("UPDATE knowledge_card SET version=version+1 WHERE id=?", target);
                    return relatedResult(p, target);
                };
        assertThatThrownBy(() -> execute(lease)).hasMessage("AGENT_RELATED_CHANGED");
        assertThat(count("ai_result", lease)).isZero();
    }

    @Test
    void restrictedRelatedSourceCannotBeConfirmed() {
        long target = savedTarget();
        var lease = start(GoalType.LINK_NOTES, true);
        model.calls = 0;
        model.next =
                (system, p) ->
                        system.contains("actions") ? relatedPlan() : relatedResult(p, target);
        execute(lease);
        var r = candidates.getResult(user, lease.taskId());
        jdbc.update(
                "UPDATE material SET access_state='RESTRICTED' WHERE id IN(SELECT c.material_id"
                        + " FROM material_chunk c JOIN card_source s ON s.chunk_id=c.id WHERE"
                        + " s.card_id=?)",
                target);
        assertThatThrownBy(
                        () ->
                                confirmations.confirm(
                                        user,
                                        "" + lease.taskId(),
                                        new ConfirmationInput(
                                                1,
                                                1,
                                                r.summary(),
                                                r.tags(),
                                                TagMode.MERGE,
                                                r.cards(),
                                                r.relations())))
                .isInstanceOfSatisfying(
                        com.learningmaterial.system.common.ApiException.class,
                        ex -> {
                            assertThat(ex.status())
                                    .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                            assertThat(ex.code()).isEqualTo("AGENT_RELATED_CHANGED");
                        });
        assertThat(status(lease)).isEqualTo("WAIT_CONFIRM");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM card_relation WHERE user_id=?",
                                Integer.class,
                                user))
                .isZero();
    }

    @Test
    void modelCannotInventRelationOutsideRecall() {
        var lease = start(GoalType.LINK_NOTES, true);
        model.next =
                (system, p) ->
                        system.contains("actions")
                                ? plan(
                                        "prepare_source",
                                        "generate_summary_cards",
                                        "validate_candidate")
                                : relatedResult(p, 999999);
        assertThatThrownBy(() -> execute(lease)).hasMessage("MODEL_OUTPUT_INVALID");
        assertThat(count("ai_result", lease)).isZero();
        assertThat(model.calls).isEqualTo(3);
    }

    long savedTarget() {
        var lease = start(GoalType.REVIEW_CARDS, false);
        execute(lease);
        var r = candidates.getResult(user, lease.taskId());
        return Long.parseLong(
                confirmations
                        .confirm(
                                user,
                                "" + lease.taskId(),
                                new ConfirmationInput(
                                        1,
                                        1,
                                        r.summary(),
                                        r.tags(),
                                        TagMode.MERGE,
                                        r.cards(),
                                        r.relations()))
                        .cardIds()
                        .getFirst());
    }

    String relatedPlan() {
        return "{\"actions\":[{\"tool\":\"prepare_source\",\"arguments\":{}},{\"tool\":\"search_personal_materials\",\"arguments\":{\"keywords\":\"知识\",\"subject\":null,\"limit\":3}},{\"tool\":\"generate_summary_cards\",\"arguments\":{}},{\"tool\":\"validate_candidate\",\"arguments\":{}}],\"stopReason\":null}";
    }

    String relatedResult(String p, long target) {
        try {
            var body =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            new ObjectMapper().readTree(model.valid(p));
            body.putArray("relations")
                    .addObject()
                    .put("sourceIndex", 0)
                    .put("targetCardId", "" + target)
                    .put("evidenceText", "主题关联，待人工核对");
            return body.toString();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void latePlannerFromOldEpochCannotExecuteActionsAndNewEpochRetainsLedger() {
        var old = start(GoalType.REVIEW_CARDS, false);
        TaskLease[] replacement = new TaskLease[1];
        model.next =
                (s, p) -> {
                    jdbc.update(
                            "UPDATE ai_task SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND"
                                    + " WHERE id=?",
                            old.taskId());
                    replacement[0] = leases.claim();
                    return plan("prepare_source", "generate_summary_cards", "validate_candidate");
                };
        execute(old);
        assertThat(count("material_chunk", old)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM model_attempt WHERE task_id=?",
                                String.class,
                                old.taskId()))
                .isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_tokens FROM model_attempt WHERE task_id=?",
                                Long.class,
                                old.taskId()))
                .isEqualTo(11L);
        model.next =
                (s, p) ->
                        s.contains("actions")
                                ? plan(
                                        "prepare_source",
                                        "generate_summary_cards",
                                        "validate_candidate")
                                : model.valid(p);
        execute(replacement[0]);
        assertThat(status(replacement[0])).isEqualTo("WAIT_CONFIRM");
        assertThat(count("model_attempt", old)).isEqualTo(3);
    }

    @Test
    void taskSnapshotCapturesExplicitModeAndAgentBudgets() throws Exception {
        var lease = start(GoalType.REVIEW_CARDS, false);
        var c =
                new ObjectMapper()
                        .readTree(
                                jdbc.queryForObject(
                                        "SELECT config_json FROM ai_task WHERE id=?",
                                        String.class,
                                        lease.taskId()));
        assertThat(c.path("processorMode").asText()).isEqualTo("agent");
        assertThat(c.path("budget").path("maxSteps").asInt()).isEqualTo(8);
        assertThat(c.path("budget").path("maxModelAttempts").asInt()).isEqualTo(8);
        assertThat(c.path("budget").path("maxPlannerAttempts").asInt()).isEqualTo(4);
        assertThat(c.path("budget").path("maxGenerationAttempts").asInt()).isEqualTo(2);
    }

    @Test
    void promptFollowingPlannerCompletesScannedPdfWithSearchInsideEightSteps() throws Exception {
        long target = savedTarget();
        var lease = startMaterial(pdf(""), new PageRange(1, 1), GoalType.LINK_NOTES, true);
        model.calls = 0;
        model.prompts.clear();
        model.next =
                (system, prompt) -> {
                    if (!system.contains("actions")) return relatedResult(prompt, target);
                    try {
                        var input = new ObjectMapper().readTree(prompt);
                        String declared =
                                system.contains("batchTools") ? "batchTools" : "allowedTools";
                        Set<String> tools = new HashSet<>();
                        input.path(declared).forEach(t -> tools.add(t.asText()));
                        if (tools.contains("extract_pdf_text")) return plan("extract_pdf_text");
                        var ordered =
                                List.of(
                                        "recognize_image",
                                        "search_personal_materials",
                                        "generate_summary_cards",
                                        "validate_candidate");
                        var actions = new ObjectMapper().createArrayNode();
                        for (String tool : ordered)
                            if (tools.contains(tool)) {
                                var action = actions.addObject().put("tool", tool);
                                if (tool.equals("search_personal_materials"))
                                    action.putObject("arguments")
                                            .put("keywords", "知识")
                                            .putNull("subject")
                                            .put("limit", 3);
                                else action.putObject("arguments");
                            }
                        return new ObjectMapper()
                                .createObjectNode()
                                .set("actions", actions)
                                .toString()
                                .replaceFirst("}$", ",\"stopReason\":null}");
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                };
        execute(lease);
        assertThat(status(lease)).isEqualTo("WAIT_CONFIRM");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT step_count FROM ai_task WHERE id=?",
                                Integer.class,
                                lease.taskId()))
                .isLessThanOrEqualTo(8);
        assertThat(ocr.calls).isEqualTo(1);
        assertThat(candidates.getResult(user, lease.taskId()).relations()).hasSize(1);
    }

    @Test
    void stagedCandidateCannotRunOcrOrChangeItsSources() throws Exception {
        var lease =
                startMaterial(
                        pdf("Readable source", ""),
                        new PageRange(1, 2),
                        GoalType.REVIEW_CARDS,
                        false);
        model.next =
                (system, prompt) ->
                        system.contains("actions")
                                ? plan(
                                        "extract_pdf_text",
                                        "generate_summary_cards",
                                        "recognize_image",
                                        "validate_candidate")
                                : model.valid(prompt);
        assertThatThrownBy(() -> execute(lease)).hasMessage("AGENT_ACTION_NOT_ALLOWED");
        assertThat(ocr.calls).isZero();
        assertThat(count("ai_result", lease)).isZero();
    }

    @Test
    void mutualRelatedConfirmationsCompleteWithoutDeadlock() throws Exception {
        long cardA = savedTarget(), cardB = savedTarget();
        jdbc.update("UPDATE knowledge_card SET title='互关联旧目标' WHERE id IN (?,?)", cardA, cardB);
        long materialA =
                jdbc.queryForObject(
                        "SELECT c.material_id FROM material_chunk c JOIN card_source s ON"
                            + " s.chunk_id=c.id WHERE s.card_id=? LIMIT 1",
                        Long.class,
                        cardA);
        long materialB =
                jdbc.queryForObject(
                        "SELECT c.material_id FROM material_chunk c JOIN card_source s ON"
                            + " s.chunk_id=c.id WHERE s.card_id=? LIMIT 1",
                        Long.class,
                        cardB);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 3; round++) {
                var a = startExisting(materialA);
                model.next =
                        (system, prompt) ->
                                system.contains("actions")
                                        ? relatedPlan()
                                                .replace(
                                                        "\"keywords\":\"知识\"",
                                                        "\"keywords\":\"互关联旧目标\"")
                                        : relatedResult(prompt, cardB);
                execute(a);
                var b = startExisting(materialB);
                model.next =
                        (system, prompt) ->
                                system.contains("actions")
                                        ? relatedPlan()
                                                .replace(
                                                        "\"keywords\":\"知识\"",
                                                        "\"keywords\":\"互关联旧目标\"")
                                        : relatedResult(prompt, cardA);
                execute(b);
                var inputA = confirmation(a);
                var inputB = confirmation(b);
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var first =
                        pool.submit(
                                () -> {
                                    barrier.await();
                                    return confirmations.confirm(user, "" + a.taskId(), inputA);
                                });
                var second =
                        pool.submit(
                                () -> {
                                    barrier.await();
                                    return confirmations.confirm(user, "" + b.taskId(), inputB);
                                });
                assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS).status())
                        .isEqualTo("SUCCEEDED");
                assertThat(second.get(15, java.util.concurrent.TimeUnit.SECONDS).status())
                        .isEqualTo("SUCCEEDED");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    TaskLease startExisting(long material) {
        int version =
                jdbc.queryForObject(
                        "SELECT version FROM material WHERE id=?", Integer.class, material);
        var task =
                tasks.submit(
                        user,
                        new TaskInput(
                                "" + material,
                                version,
                                UUID.randomUUID().toString(),
                                GoalType.LINK_NOTES,
                                null,
                                null,
                                true,
                                true));
        var lease = leases.claim();
        assertThat(lease.taskId()).isEqualTo(Long.parseLong(task.id()));
        return lease;
    }

    ConfirmationInput confirmation(TaskLease lease) {
        var result = candidates.getResult(user, lease.taskId());
        return new ConfirmationInput(
                result.resultVersion(),
                result.sourceVersion(),
                result.summary(),
                result.tags(),
                TagMode.MERGE,
                result.cards(),
                result.relations());
    }

    void execute(TaskLease l) {
        assertThat(processor.getIfAvailable()).as("agent processor enabled").isNotNull();
        processor.getObject().execute(l);
    }

    String status(TaskLease l) {
        return jdbc.queryForObject(
                "SELECT status FROM ai_task WHERE id=?", String.class, l.taskId());
    }

    int count(String table, TaskLease l) {
        return table.equals("material_chunk")
                ? jdbc.queryForObject(
                        "SELECT COUNT(*) FROM material_chunk WHERE material_id IN(SELECT"
                                + " material_id FROM ai_task WHERE id=?)",
                        Integer.class,
                        l.taskId())
                : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE task_id=?",
                        Integer.class,
                        l.taskId());
    }

    TaskLease start(GoalType goal, boolean related) {
        jdbc.update(
                "INSERT INTO"
                    + " material(user_id,title,material_type,note_text,version,status,access_state,created_at,updated_at)"
                    + " VALUES(?,'智能体测试','NOTE','准确原文知识片段',1,'ACTIVE','NORMAL',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                user);
        long m =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM material WHERE user_id=?", Long.class, user);
        var t =
                tasks.submit(
                        user,
                        new TaskInput(
                                "" + m,
                                1,
                                UUID.randomUUID().toString(),
                                goal,
                                null,
                                null,
                                related,
                                true));
        var l = leases.claim();
        assertThat(l.taskId()).isEqualTo(Long.parseLong(t.id()));
        return l;
    }

    static String plan(String... tools) {
        return "{\"actions\":["
                + Arrays.stream(tools)
                        .map(t -> "{\"tool\":\"" + t + "\",\"arguments\":{}}")
                        .reduce((a, b) -> a + "," + b)
                        .orElse("")
                + "],\"stopReason\":null}";
    }
}
