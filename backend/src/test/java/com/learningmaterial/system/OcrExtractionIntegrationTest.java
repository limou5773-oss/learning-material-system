package com.learningmaterial.system;

import com.learningmaterial.system.dto.AiTaskDtos.*;
import com.learningmaterial.system.dto.TaskLease;
import com.learningmaterial.system.entity.MaterialChunkRow;
import com.learningmaterial.system.integration.ocr.OcrClient;
import com.learningmaterial.system.integration.ocr.OcrException;
import com.learningmaterial.system.integration.storage.FileStorage;
import com.learningmaterial.system.mapper.MaterialChunkMapper;
import com.learningmaterial.system.service.AiTaskService;
import com.learningmaterial.system.service.AuthService;
import com.learningmaterial.system.service.CardService;
import com.learningmaterial.system.service.MaterialService;
import com.learningmaterial.system.service.TaskCandidateService;
import com.learningmaterial.system.service.TaskConfirmationService;
import com.learningmaterial.system.service.TaskLeaseService;
import com.learningmaterial.system.task.MockTaskProcessor;
import com.learningmaterial.system.task.TaskExecutionException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Provider is an explicit deterministic test stub; these tests do not measure real OCR accuracy.
 */
@SpringBootTest(properties = {"lms.task.processor.mode=mock", "lms.ocr.enabled=true"})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class OcrExtractionIntegrationTest {
    @TestConfiguration
    static class StubConfiguration {
        @Bean
        @Primary
        StubOcrClient stubOcrClient() {
            return new StubOcrClient();
        }
    }

    static class StubOcrClient implements OcrClient {
        int calls;
        Supplier<Result> next = () -> new Result("Recognized source text", .95, "safe-request");

        public String providerKey() {
            return "TEST_STUB_V1";
        }

        public Result recognize(byte[] image) {
            calls++;
            assertThat(image.length).isLessThanOrEqualTo(1_048_576);
            try {
                BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(image));
                assertThat(Math.max(decoded.getWidth(), decoded.getHeight()))
                        .isLessThanOrEqualTo(4096);
                assertThat((long) decoded.getWidth() * decoded.getHeight())
                        .isLessThanOrEqualTo(12_000_000);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            return next.get();
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired com.learningmaterial.system.service.AuthService auth;
    @Autowired com.learningmaterial.system.service.CardService cards;
    @Autowired TaskConfirmationService confirmation;
    @Autowired com.learningmaterial.system.service.MaterialService materials;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired AiTaskService tasks;
    @Autowired TaskLeaseService leases;
    @Autowired MockTaskProcessor processor;
    @Autowired TaskCandidateService candidates;
    @Autowired StubOcrClient ocr;
    @MockitoSpyBean FileStorage storage;
    @MockitoSpyBean MaterialChunkMapper chunks;
    long user, other;
    List<String> files = new ArrayList<>();

    @BeforeEach
    void setup() {
        ocr.calls = 0;
        ocr.next = () -> new OcrClient.Result("Recognized source text", .95, "safe-request");
        String name = "ocr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var registered =
                auth.register(
                        new com.learningmaterial.system.dto.AuthDtos.RegistrationInput(
                                name, "Test-password-123", name));
        var otherRegistered =
                auth.register(
                        new com.learningmaterial.system.dto.AuthDtos.RegistrationInput(
                                name + "x", "Test-password-123", name + "x"));
        assertThat(registered.status()).isEqualTo("ENABLED");
        user = Long.parseLong(registered.id());
        other = Long.parseLong(otherRegistered.id());
    }

    @AfterEach
    void cleanup() {
        reset(storage, chunks);
        jdbc.update(
                "DELETE FROM card_source WHERE card_id IN (SELECT id FROM knowledge_card WHERE"
                        + " user_id=?)",
                user);
        jdbc.update("DELETE FROM knowledge_card WHERE user_id=?", user);
        for (String table : List.of("ai_result", "ai_tool_call", "ocr_attempt")) {
            if (exists(table))
                jdbc.update(
                        "DELETE FROM "
                                + table
                                + " WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)",
                        user);
        }
        jdbc.update("DELETE FROM ai_task WHERE user_id=?", user);
        for (String table :
                List.of("material_chunk", "ocr_page_cache", "pdf_page_cache", "material_tag"))
            if (exists(table))
                jdbc.update(
                        "DELETE FROM "
                                + table
                                + " WHERE material_id IN (SELECT id FROM material WHERE user_id=?)",
                        user);
        jdbc.update("DELETE FROM material WHERE user_id=?", user);
        jdbc.update("DELETE FROM tag WHERE user_id=?", user);
        jdbc.update("DELETE FROM app_user WHERE id IN (?,?)", user, other);
        files.forEach(storage::deleteQuietly);
    }

    boolean exists(String table) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                + " table_schema=DATABASE() AND table_name=?",
                        Integer.class,
                        table)
                > 0;
    }

    @Test
    void imageUsesRecognizedTextAndKeepsMockGeneration() throws Exception {
        long m = image();
        var l = start(m, null);
        processor.execute(l);
        assertThat(ocr.calls).isEqualTo(1);
        var result = candidates.getResult(user, l.taskId());
        assertThat(result.generationMode()).isEqualTo("MOCK");
        assertThat(candidates.getSources(user, l.taskId()))
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.extractionMethod()).isEqualTo("OCR");
                            assertThat(s.pageNo()).isNull();
                            assertThat(s.content()).isEqualTo("Recognized source text");
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ocr_attempt WHERE task_id=?",
                                String.class,
                                l.taskId()))
                .isEqualTo("SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cost FROM ocr_attempt WHERE task_id=?",
                                Object.class,
                                l.taskId()))
                .isNull();
        assertThat(tasks.steps(user, Long.toString(l.taskId()), 1, 20).items().toString())
                .contains("recognize_image")
                .doesNotContain("Recognized source text");
    }

    @Test
    void mixedPdfOcrProcessesOnlySelectedPagesWithoutText() throws Exception {
        long m = pdf("Outside", "Real text", "", "");
        var l = start(m, new PageRange(2, 3));
        processor.execute(l);
        assertThat(ocr.calls).isEqualTo(1);
        assertThat(candidates.getResult(user, l.taskId()).processedPages()).containsExactly(2, 3);
        assertThat(candidates.getResult(user, l.taskId()).warnings().toString())
                .doesNotContain("本次未生成");
        assertThat(candidates.getSources(user, l.taskId()))
                .extracting(SourceView::extractionMethod)
                .containsExactly("PDF_TEXT", "OCR");
        assertThat(candidates.getSources(user, l.taskId()))
                .extracting(SourceView::pageNo)
                .containsExactly(2, 3);
    }

    @Test
    void cacheHitAvoidsProviderAndFileAndVersionIsIsolated() throws Exception {
        long m = image();
        var first = start(m, null);
        processor.execute(first);
        doThrow(new AssertionError("cache must not read file")).when(storage).open(anyString());
        var second = start(m, null);
        processor.execute(second);
        assertThat(ocr.calls).isEqualTo(1);
        assertThat(attempts(second)).isZero();
        assertThat(tasks.steps(user, Long.toString(second.taskId()), 1, 20).items().toString())
                .contains("缓存");
        reset(storage);
        jdbc.update("UPDATE material SET version=2 WHERE id=?", m);
        var next = start(m, null);
        processor.execute(next);
        assertThat(ocr.calls).isEqualTo(2);
        assertThat(count("ocr_page_cache", m)).isEqualTo(2);
    }

    @Test
    void retriesTimeoutOnlyAndRecordsActualAttempts() throws Exception {
        long m = image();
        ocr.next =
                () -> {
                    if (ocr.calls == 1) throw new OcrException("OCR_TIMEOUT", true, true);
                    return new OcrClient.Result("After retry", .9, "r2");
                };
        var l = start(m, null);
        processor.execute(l);
        assertThat(ocr.calls).isEqualTo(2);
        assertThat(attempts(l)).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM ocr_attempt WHERE task_id=? ORDER BY"
                                        + " attempt_no",
                                String.class,
                                l.taskId()))
                .containsExactly("FAILED", "SUCCESS");
    }

    @Test
    void nonRetryableConfigurationFailureIsNeverSent() throws Exception {
        long m = image();
        ocr.next =
                () -> {
                    throw new OcrException("OCR_NOT_CONFIGURED", false, false);
                };
        var l = start(m, null);
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("OCR_NOT_CONFIGURED");
        assertThat(ocr.calls).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT dispatched FROM ocr_attempt WHERE task_id=?",
                                Boolean.class,
                                l.taskId()))
                .isFalse();
        assertThat(count("ocr_page_cache", m)).isZero();
    }

    @Test
    void allEmptyImageFailsAndCachesEmptyWithoutInventedText() throws Exception {
        long m = image();
        ocr.next = () -> new OcrClient.Result(" ", null, "empty");
        var l = start(m, null);
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("OCR_EMPTY_RESULT");
        assertThat(count("material_chunk", m)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT extraction_status FROM ocr_page_cache WHERE material_id=?",
                                String.class,
                                m))
                .isEqualTo("EMPTY");
    }

    @Test
    void emptyPdfPagesRemainUnprocessedAndLowUnknownConfidenceWarn() throws Exception {
        long m = pdf("Actual text", "", "");
        ocr.next =
                () ->
                        ocr.calls == 1
                                ? new OcrClient.Result("", null, "empty")
                                : new OcrClient.Result("Low confidence", .5, "low");
        var l = start(m, new PageRange(1, 3));
        processor.execute(l);
        var r = candidates.getResult(user, l.taskId());
        assertThat(r.processedPages()).containsExactly(1, 3);
        assertThat(r.warnings().toString()).contains("2", "3", "置信度");
    }

    @Test
    void unknownConfidenceExplicitlyWarns() throws Exception {
        long m = image();
        ocr.next = () -> new OcrClient.Result("Unknown confidence", null, "r");
        var l = start(m, null);
        processor.execute(l);
        assertThat(candidates.getResult(user, l.taskId()).warnings().toString()).contains("置信度未知");
    }

    @Test
    void overlongResultDoesNotPublishTruncatedSourcesOrCache() throws Exception {
        long m = image();
        ocr.next = () -> new OcrClient.Result("A".repeat(60001), .9, "r");
        var l = start(m, null);
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("OCR_TEXT_LIMIT_EXCEEDED");
        assertThat(count("ocr_page_cache", m)).isZero();
        assertThat(count("material_chunk", m)).isZero();
    }

    @Test
    void fullTaskAttemptBudgetStopsBeforeTwentyFirstRequest() throws Exception {
        String[] blank = new String[20];
        Arrays.fill(blank, "");
        long m = pdf(blank);
        ocr.next =
                () -> {
                    if (ocr.calls % 2 == 1) throw new OcrException("OCR_TIMEOUT", true, true);
                    return new OcrClient.Result("Text", .9, "r");
                };
        var l = start(m, new PageRange(1, 20));
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("OCR_ATTEMPT_LIMIT");
        assertThat(ocr.calls).isEqualTo(20);
        assertThat(attempts(l)).isEqualTo(20);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "cancel",
                "epoch",
                "expired",
                "version",
                "restricted",
                "owner",
                "storage",
                "hash"
            })
    void lateResponsesNeverPublishBusinessData(String change) throws Exception {
        long m = image();
        var l = start(m, null);
        ocr.next =
                () -> {
                    switch (change) {
                        case "cancel" -> tasks.cancel(user, Long.toString(l.taskId()));
                        case "epoch" ->
                                jdbc.update(
                                        "UPDATE ai_task SET execution_epoch=execution_epoch+1 WHERE"
                                                + " id=?",
                                        l.taskId());
                        case "expired" ->
                                jdbc.update(
                                        "UPDATE ai_task SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1"
                                                + " SECOND WHERE id=?",
                                        l.taskId());
                        case "version" ->
                                jdbc.update("UPDATE material SET version=version+1 WHERE id=?", m);
                        case "restricted" ->
                                jdbc.update(
                                        "UPDATE material SET access_state='RESTRICTED' WHERE id=?",
                                        m);
                        case "owner" ->
                                jdbc.update("UPDATE material SET user_id=? WHERE id=?", other, m);
                        case "storage" ->
                                jdbc.update(
                                        "UPDATE material SET"
                                            + " storage_key='aa/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png'"
                                            + " WHERE id=?",
                                        m);
                        case "hash" ->
                                jdbc.update(
                                        "UPDATE material SET file_hash='changed' WHERE id=?", m);
                    }
                    return new OcrClient.Result("Late text", .9, "late");
                };
        try {
            processor.execute(l);
        } catch (TaskExecutionException ignored) {
        } finally {
            if (change.equals("owner"))
                jdbc.update("UPDATE material SET user_id=? WHERE id=?", user, m);
        }
        assertThat(count("ocr_page_cache", m)).isZero();
        assertThat(count("material_chunk", m)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ocr_attempt WHERE task_id=?",
                                String.class,
                                l.taskId()))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void changedProviderCannotOverwriteImmutableCache() throws Exception {
        long m = image();
        var first = start(m, null);
        processor.execute(first);
        jdbc.update("UPDATE ocr_page_cache SET provider_key='OLD_PROVIDER' WHERE material_id=?", m);
        var next = start(m, null);
        assertThatThrownBy(() -> processor.execute(next)).hasMessage("OCR_CACHE_CONFLICT");
        assertThat(ocr.calls).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT provider_key FROM ocr_page_cache WHERE material_id=?",
                                String.class,
                                m))
                .isEqualTo("OLD_PROVIDER");
    }

    @Test
    void accessChangeDuringFileReadPreventsDispatch() throws Exception {
        long m = image();
        var l = start(m, null);
        doAnswer(
                        invocation -> {
                            jdbc.update(
                                    "UPDATE material SET access_state='RESTRICTED' WHERE id=?", m);
                            return invocation.callRealMethod();
                        })
                .when(storage)
                .open(anyString());
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("SOURCE_UNAVAILABLE");
        assertThat(ocr.calls).isZero();
        assertThat(attempts(l)).isZero();
        assertThat(count("ocr_page_cache", m)).isZero();
    }

    @Test
    void combinedPdfAndOcrChunkBudgetDoesNotTruncate() throws Exception {
        long m = pdf("P".repeat(58000), "");
        ocr.next = () -> new OcrClient.Result("O".repeat(2001), .9, "r");
        var l = start(m, new PageRange(1, 2));
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("OCR_TEXT_LIMIT_EXCEEDED");
        assertThat(count("ocr_page_cache", m)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM material_chunk WHERE material_id=? AND"
                                        + " extraction_method='OCR'",
                                Integer.class,
                                m))
                .isZero();
    }

    @Test
    void publicationFailureRollsBackCacheAndSources() throws Exception {
        long m = image();
        var l = start(m, null);
        doThrow(new TaskExecutionException("SOURCE_CHUNK_CONFLICT"))
                .when(chunks)
                .insertOrReuse(any(MaterialChunkRow.class));
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("SOURCE_CHUNK_CONFLICT");
        assertThat(count("ocr_page_cache", m)).isZero();
        assertThat(count("material_chunk", m)).isZero();
    }

    @Test
    void imagePreparationBoundsLargeImageBeforeProvider() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(6000, 2200, BufferedImage.TYPE_INT_RGB), "png", out);
        long m = material("IMAGE", storage.store(out.toByteArray(), "png"), null);
        var l = start(m, null);
        processor.execute(l);
        assertThat(ocr.calls).isEqualTo(1);
    }

    @Test
    void invalidImageNeverDispatches() throws Exception {
        long m =
                material(
                        "IMAGE",
                        storage.store(
                                "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                "png"),
                        null);
        var l = start(m, null);
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("OCR_IMAGE_INVALID");
        assertThat(ocr.calls).isZero();
        assertThat(attempts(l)).isZero();
    }

    @Test
    void preservesAllUtf16TextAndNeverSplitsSurrogatePairs() throws Exception {
        String text = "A".repeat(1999) + "\uD83D\uDE00" + "B".repeat(2500);
        ocr.next = () -> new OcrClient.Result(text, .9, "r");
        long m = image();
        var l = start(m, null);
        processor.execute(l);
        var sources = candidates.getSources(user, l.taskId());
        assertThat(sources).hasSize(3);
        assertThat(sources.stream().map(SourceView::content).reduce("", String::concat))
                .isEqualTo(text);
        assertThat(sources)
                .allSatisfy(
                        s -> {
                            assertThat(s.content().length()).isLessThanOrEqualTo(2000);
                            assertThat(
                                            Character.isHighSurrogate(
                                                    s.content().charAt(s.content().length() - 1)))
                                    .isFalse();
                        });
    }

    @Test
    void scannedPdfAllEmptyFailsButCachesEmptyPages() throws Exception {
        long m = pdf("", "");
        ocr.next = () -> new OcrClient.Result("", null, "empty");
        var l = start(m, new PageRange(1, 2));
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("OCR_EMPTY_RESULT");
        assertThat(count("ocr_page_cache", m)).isEqualTo(2);
        assertThat(count("material_chunk", m)).isZero();
        assertThat(attempts(l)).isEqualTo(2);
    }

    @Test
    void twentyScannedPagesUseOneOcrActionWithinStepBudget() throws Exception {
        String[] blank = new String[20];
        Arrays.fill(blank, "");
        long m = pdf(blank);
        var l = start(m, new PageRange(1, 20));
        processor.execute(l);
        assertThat(ocr.calls).isEqualTo(20);
        assertThat(candidates.getResult(user, l.taskId()).processedPages()).hasSize(20);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ai_tool_call WHERE task_id=? AND"
                                        + " tool_name='recognize_image'",
                                Integer.class,
                                l.taskId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT step_count FROM ai_task WHERE id=?",
                                Integer.class,
                                l.taskId()))
                .isEqualTo(4);
    }

    @Test
    void repeatTimeoutStopsAtTwoAttemptsAndCostsRemainUnknown() throws Exception {
        long m = image();
        ocr.next =
                () -> {
                    throw new OcrException("OCR_TIMEOUT", true, true);
                };
        var l = start(m, null);
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("OCR_TIMEOUT");
        assertThat(attempts(l)).isEqualTo(2);
        assertThat(ocr.calls).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ocr_attempt WHERE task_id=? AND dispatched=1"
                                        + " AND cost IS NULL",
                                Integer.class,
                                l.taskId()))
                .isEqualTo(2);
    }

    @Test
    void canceledInFlightAttemptIsUnknownBeforeResponseReturns() throws Exception {
        long m = image();
        var l = start(m, null);
        ocr.next =
                () -> {
                    tasks.cancel(user, Long.toString(l.taskId()));
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT status FROM ocr_attempt WHERE task_id=?",
                                            String.class,
                                            l.taskId()))
                            .isEqualTo("UNKNOWN");
                    return new OcrClient.Result("Late", .9, "late");
                };
        processor.execute(l);
        assertThat(count("ocr_page_cache", m)).isZero();
    }

    @Test
    void disabledUserCannotPublishProviderResponse() throws Exception {
        long m = image();
        var l = start(m, null);
        ocr.next =
                () -> {
                    jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?", user);
                    return new OcrClient.Result("Late", .9, "late");
                };
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("SOURCE_UNAVAILABLE");
        assertThat(count("ocr_page_cache", m)).isZero();
        assertThat(count("material_chunk", m)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"storage", "hash"})
    void queuedFileFingerprintChangesPreventDispatch(String change) throws Exception {
        long m = image();
        var l = start(m, null);
        if (change.equals("storage"))
            jdbc.update(
                    "UPDATE material SET storage_key='aa/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png'"
                            + " WHERE id=?",
                    m);
        else jdbc.update("UPDATE material SET file_hash='changed' WHERE id=?", m);
        assertThatThrownBy(() -> processor.execute(l)).hasMessage("SOURCE_VERSION_CHANGED");
        assertThat(ocr.calls).isZero();
        assertThat(attempts(l)).isZero();
    }

    @Test
    void deletingMaterialMarksInflightAttemptUnknownImmediately() throws Exception {
        long m = image();
        var l = start(m, null);
        ocr.next =
                () -> {
                    materials.delete(user, Long.toString(m), 1);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT status FROM ocr_attempt WHERE task_id=?",
                                            String.class,
                                            l.taskId()))
                            .isEqualTo("UNKNOWN");
                    return new OcrClient.Result("Late", .9, "late");
                };
        processor.execute(l);
        assertThat(count("ocr_page_cache", m)).isZero();
    }

    @Test
    void confirmedCardRetainsOcrMethodAndQualityButRestrictedSourceHidesQuality() throws Exception {
        long m = image();
        ocr.next = () -> new OcrClient.Result("Confirmed OCR source", .5, "r");
        var l = start(m, null);
        processor.execute(l);
        var result = candidates.getResult(user, l.taskId());
        var receipt =
                confirmation.confirm(
                        user,
                        Long.toString(l.taskId()),
                        new ConfirmationInput(
                                1,
                                1,
                                result.summary(),
                                result.tags(),
                                TagMode.MERGE,
                                result.cards(),
                                result.relations()));
        String card = receipt.cardIds().getFirst();
        var available = json.valueToTree(cards.sources(user, card).items().getFirst());
        assertThat(available.path("extractionMethod").asText()).isEqualTo("OCR");
        assertThat(available.path("qualityNote").asText()).contains("置信度较低");
        jdbc.update("UPDATE material SET access_state='RESTRICTED' WHERE id=?", m);
        var restricted = json.valueToTree(cards.sources(user, card).items().getFirst());
        assertThat(restricted.path("qualityNote").isNull()).isTrue();
        assertThat(restricted.path("content").isNull()).isTrue();
        jdbc.update("UPDATE material SET access_state='NORMAL' WHERE id=?", m);
        int currentVersion =
                jdbc.queryForObject("SELECT version FROM material WHERE id=?", Integer.class, m);
        materials.delete(user, Long.toString(m), currentVersion);
        var deleted = json.valueToTree(cards.sources(user, card).items().getFirst());
        assertThat(deleted.path("qualityNote").isNull()).isTrue();
        assertThat(deleted.path("content").isNull()).isTrue();
    }

    @Test
    void lateOldEpochCannotMarkNewEpochRequestUnknown() throws Exception {
        long m = image();
        var old = start(m, null);
        ocr.next =
                () -> {
                    jdbc.update(
                            "UPDATE ai_task SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND"
                                    + " WHERE id=?",
                            old.taskId());
                    var newer = leases.claim();
                    assertThat(newer.epoch()).isEqualTo(old.epoch() + 1);
                    jdbc.update(
                            "INSERT INTO"
                                + " ocr_attempt(task_id,execution_epoch,page_no,attempt_no,provider_key,dispatched,status,started_at)"
                                + " VALUES(?,?,0,2,'TEST_STUB_V1',1,'RUNNING',UTC_TIMESTAMP(6))",
                            old.taskId(),
                            newer.epoch());
                    return new OcrClient.Result("Late old worker", .9, "late");
                };
        processor.execute(old);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ocr_attempt WHERE task_id=? AND"
                                        + " execution_epoch=?",
                                String.class,
                                old.taskId(),
                                old.epoch()))
                .isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ocr_attempt WHERE task_id=? AND"
                                        + " execution_epoch=?",
                                String.class,
                                old.taskId(),
                                old.epoch() + 1))
                .isEqualTo("RUNNING");
        assertThat(count("ocr_page_cache", m)).isZero();
    }

    private int attempts(TaskLease l) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ocr_attempt WHERE task_id=?", Integer.class, l.taskId());
    }

    private int count(String table, long m) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE material_id=?", Integer.class, m);
    }

    private long image() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", out);
        return material("IMAGE", storage.store(out.toByteArray(), "png"), null);
    }

    private long pdf(String... texts) throws Exception {
        try (PDDocument doc = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            for (String text : texts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (!text.isEmpty())
                    try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                        s.beginText();
                        s.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                        s.newLineAtOffset(20, 700);
                        s.showText(text);
                        s.endText();
                    }
            }
            doc.save(out);
            return material("PDF", storage.store(out.toByteArray(), "pdf"), texts.length);
        }
    }

    private long material(String type, String key, Integer pages) {
        files.add(key);
        jdbc.update(
                "INSERT INTO"
                    + " material(user_id,title,material_type,storage_key,page_count,version,status,access_state,created_at,updated_at)"
                    + " VALUES(?,'Title is not"
                    + " OCR',?,?,?,1,'ACTIVE','NORMAL',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                user,
                type,
                key,
                pages);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM material WHERE user_id=?", Long.class, user);
    }

    private TaskLease start(long m, PageRange range) {
        int version =
                jdbc.queryForObject("SELECT version FROM material WHERE id=?", Integer.class, m);
        var t =
                tasks.submit(
                        user,
                        new TaskInput(
                                Long.toString(m),
                                version,
                                UUID.randomUUID().toString(),
                                GoalType.REVIEW_CARDS,
                                null,
                                range,
                                false,
                                true));
        var l = leases.claim();
        assertThat(l.taskId()).isEqualTo(Long.parseLong(t.id()));
        return l;
    }
}
