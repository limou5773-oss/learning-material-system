package com.learningmaterial.system;

import com.learningmaterial.system.card.CardService;
import com.learningmaterial.system.material.FileStorage;
import com.learningmaterial.system.task.*;
import com.learningmaterial.system.task.AiTaskDtos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"lms.task.processor.mode=mock", "logging.level.org.apache.fontbox=ERROR", "logging.level.org.apache.pdfbox.pdmodel.font=ERROR"})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*learning_material_auth_test.*")
class PdfExtractionIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AiTaskService tasks;
    @Autowired TaskLeaseService leases;
    @Autowired MockTaskProcessor processor;
    @Autowired TaskCandidateService candidates;
    @Autowired TaskConfirmationService confirmation;
    @Autowired CardService cards;
    @MockitoSpyBean FileStorage storage;
    long user;
    long other;
    List<String> files = new ArrayList<>();

    @BeforeEach void setup() {
        String name = "pdfextract_" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO app_user(username,password_hash,display_name,role,status,created_at,updated_at) VALUES(?,'unused',?,'USER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", name, name);
        user = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, name);
        jdbc.update("INSERT INTO app_user(username,password_hash,display_name,role,status,created_at,updated_at) VALUES(?,'unused',?,'USER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", name + "x", name);
        other = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, name + "x");
    }

    @AfterEach void cleanup() {
        reset(storage);
        jdbc.update("DELETE FROM card_source WHERE card_id IN (SELECT id FROM knowledge_card WHERE user_id=?)", user);
        jdbc.update("DELETE FROM knowledge_card WHERE user_id=?", user);
        jdbc.update("DELETE FROM ai_result WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", user);
        jdbc.update("DELETE FROM ai_tool_call WHERE task_id IN (SELECT id FROM ai_task WHERE user_id=?)", user);
        jdbc.update("DELETE FROM ai_task WHERE user_id=?", user);
        jdbc.update("DELETE FROM material_chunk WHERE material_id IN (SELECT id FROM material WHERE user_id=?)", user);
        // The initial RED run has no V8 table yet.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='pdf_page_cache'", Integer.class) > 0)
            jdbc.update("DELETE FROM pdf_page_cache WHERE material_id IN (SELECT id FROM material WHERE user_id=?)", user);
        jdbc.update("DELETE FROM material_tag WHERE material_id IN (SELECT id FROM material WHERE user_id=?)", user);
        jdbc.update("DELETE FROM material WHERE user_id=?", user);
        jdbc.update("DELETE FROM tag WHERE user_id=?", user);
        jdbc.update("DELETE FROM app_user WHERE id=?", user);
        jdbc.update("DELETE FROM app_user WHERE id=?", other);
        files.forEach(storage::deleteQuietly);
    }

    @Test void readsOnlySelectedRealPagesAndConfirmedCardsKeepPageSources() throws Exception {
        long material = material("Excluded first", "Selected second", "Selected third", "Excluded last");
        var lease = start(material, 1, 2, 3);
        processor.execute(lease);
        var result = candidates.getResult(user, lease.taskId());
        assertThat(result.totalPages()).isEqualTo(4);
        assertThat(result.processedPages()).containsExactly(2, 3);
        assertThat(result.generationMode()).isEqualTo("MOCK");
        var sources = candidates.getSources(user, lease.taskId());
        assertThat(sources).extracting(SourceView::pageNo).containsExactly(2, 3);
        assertThat(sources).allSatisfy(s -> assertThat(s.extractionMethod()).isEqualTo("PDF_TEXT"));
        assertThat(sources.toString()).contains("Selected second", "Selected third").doesNotContain("Excluded");
        var receipt = confirmation.confirm(user, Long.toString(lease.taskId()), new ConfirmationInput(1, 1,
            result.summary(), result.tags(), TagMode.MERGE, result.cards(), result.relations()));
        assertThat(cards.get(user, receipt.cardIds().getFirst()).generationMode()).isEqualTo("MOCK");
        assertThat(cards.sources(user, receipt.cardIds().getFirst()).items().getFirst().pageNo()).isEqualTo(2);
    }

    @Test void mixedPagesWarnAndEmptyPagesAreCachedWithoutPretendSources() throws Exception {
        long material = material("Actual first", "", "Actual third");
        var lease = start(material, 1, 1, 3);
        processor.execute(lease);
        var result = candidates.getResult(user, lease.taskId());
        assertThat(result.processedPages()).containsExactly(1, 3);
        assertThat(result.warnings().toString()).contains("2", "OCR");
        assertThat(jdbc.queryForObject("SELECT extraction_status FROM pdf_page_cache WHERE material_id=? AND page_no=2", String.class, material)).isEqualTo("NEEDS_OCR");
        assertThat(jdbc.queryForObject("SELECT page_text FROM pdf_page_cache WHERE material_id=? AND page_no=2", String.class, material)).isEmpty();
    }

    @Test void allEmptyPagesFailSafelyAndRemainCached() throws Exception {
        long material = material("", "");
        var lease = start(material, 1, 1, 2);
        assertThatThrownBy(() -> processor.execute(lease)).isInstanceOf(TaskExecutionException.class).hasMessage("PDF_OCR_REQUIRED");
        leases.fail(lease, "PDF_OCR_REQUIRED");
        assertThat(tasks.get(user, Long.toString(lease.taskId())).errorCode()).isEqualTo("PDF_OCR_REQUIRED");
        assertThat(tasks.steps(user, Long.toString(lease.taskId()), 1, 20).items().toString()).contains("OCR");
        assertThat(count("material_chunk", material)).isZero();
        assertThat(count("pdf_page_cache", material)).isEqualTo(2);
    }

    @Test void overlappingRangesReuseImmutableChunksAndCachedPagesNeedNoFile() throws Exception {
        long material = material("First", "Shared second", "Third");
        var first = start(material, 1, 1, 2);
        processor.execute(first);
        String secondId = candidates.getSources(user, first.taskId()).get(1).chunkId();
        var second = start(material, 1, 2, 3);
        processor.execute(second);
        assertThat(candidates.getSources(user, second.taskId()).getFirst().chunkId()).isEqualTo(secondId);
        assertThat(count("pdf_page_cache", material)).isEqualTo(3);
        doThrow(new AssertionError("cached pages must not open file")).when(storage).open(anyString());
        var cached = start(material, 1, 2, 2);
        processor.execute(cached);
        assertThat(candidates.getSources(user, cached.taskId()).getFirst().chunkId()).isEqualTo(secondId);
    }

    @Test void versionChangesCreateIsolatedCacheAndChunkIds() throws Exception {
        long material = material("Version original");
        var first = start(material, 1, 1, 1);
        processor.execute(first);
        String firstId = candidates.getSources(user, first.taskId()).getFirst().chunkId();
        jdbc.update("UPDATE material SET version=2 WHERE id=?", material);
        var next = start(material, 2, 1, 1);
        processor.execute(next);
        assertThat(candidates.getSources(user, next.taskId()).getFirst().chunkId()).isNotEqualTo(firstId);
        assertThat(count("pdf_page_cache", material)).isEqualTo(2);
    }

    @Test void excessiveTextFailsWithoutTruncatedSourcesOrCache() throws Exception {
        long material = material("A".repeat(60001));
        var lease = start(material, 1, 1, 1);
        assertThatThrownBy(() -> processor.execute(lease)).isInstanceOf(TaskExecutionException.class).hasMessage("PDF_TEXT_LIMIT_EXCEEDED");
        assertThat(count("material_chunk", material)).isZero();
        assertThat(count("pdf_page_cache", material)).isZero();
    }

    @Test void savesEveryCharacterAcrossBoundedPageChunks() throws Exception {
        String text = "Complete text ".repeat(350);
        long material = material(text);
        var lease = start(material, 1, 1, 1);
        processor.execute(lease);
        var sources = candidates.getSources(user, lease.taskId());
        assertThat(sources).hasSize(3).allSatisfy(s -> assertThat(s.content().length()).isLessThanOrEqualTo(2000));
        String joined = sources.stream().map(SourceView::content).reduce("", String::concat);
        assertThat(joined).startsWith(text);
        assertThat(joined).isEqualTo(jdbc.queryForObject("SELECT page_text FROM pdf_page_cache WHERE material_id=?", String.class, material));
    }

    @Test void tooManyChunksAcrossPagesFailsRatherThanDroppingPages() throws Exception {
        String[] pages = new String[20]; Arrays.fill(pages, "B".repeat(2001));
        long material = material(pages);
        var lease = start(material, 1, 1, 20);
        assertThatThrownBy(() -> processor.execute(lease)).isInstanceOf(TaskExecutionException.class).hasMessage("PDF_TEXT_LIMIT_EXCEEDED");
        assertThat(count("material_chunk", material)).isZero();
        assertThat(count("pdf_page_cache", material)).isZero();
    }

    @Test void cachedTextWithLongWhitespaceStillGeneratesValidOffsetCitations() throws Exception {
        long material = material("Visible after spaces.");
        String cachedText = " ".repeat(2100) + "Visible after spaces.";
        jdbc.update("INSERT INTO pdf_page_cache(material_id,material_version,page_no,total_pages,extraction_status,page_text,created_at) VALUES(?,1,1,1,'TEXT',?,UTC_TIMESTAMP(6))", material, cachedText);
        doThrow(new AssertionError("cached page must not be reopened")).when(storage).open(anyString());
        var lease = start(material, 1, 1, 1);
        processor.execute(lease);
        var result = candidates.getResult(user, lease.taskId());
        assertThat(result.processedPages()).containsExactly(1);
        assertThat(result.cards()).hasSize(1);
        assertThat(result.summary()).contains("Visible after spaces.");
        var ref = result.cards().getFirst().sources().getFirst();
        assertThat(ref.startOffset()).isEqualTo(100);
        assertThat(ref.quoteText()).isEqualTo("Visible after spaces.");
        assertThat(count("material_chunk", material)).isEqualTo(2);
        String complete = jdbc.queryForList("SELECT content FROM material_chunk WHERE material_id=? ORDER BY chunk_no", String.class, material)
            .stream().reduce("", String::concat);
        assertThat(complete).isEqualTo(cachedText);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "epoch", "expired", "version", "restricted", "owner", "type"})
    void changesDuringFileReadCannotWriteLateSourcesOrCache(String change) throws Exception {
        long material = material("Must never be published");
        var lease = start(material, 1, 1, 1);
        doAnswer(invocation -> {
            switch (change) {
                case "cancel" -> tasks.cancel(user, Long.toString(lease.taskId()));
                case "epoch" -> jdbc.update("UPDATE ai_task SET execution_epoch=execution_epoch+1 WHERE id=?", lease.taskId());
                case "expired" -> jdbc.update("UPDATE ai_task SET lease_until=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), lease.taskId());
                case "version" -> jdbc.update("UPDATE material SET version=version+1 WHERE id=?", material);
                case "restricted" -> jdbc.update("UPDATE material SET access_state='RESTRICTED' WHERE id=?", material);
                case "owner" -> jdbc.update("UPDATE material SET user_id=? WHERE id=?", other, material);
                case "type" -> jdbc.update("UPDATE material SET material_type='IMAGE' WHERE id=?", material);
            }
            return invocation.callRealMethod();
        }).when(storage).open(anyString());
        try { processor.execute(lease); } catch (TaskExecutionException expected) { }
        finally { if ("owner".equals(change)) jdbc.update("UPDATE material SET user_id=? WHERE id=?", user, material); }
        assertThat(count("material_chunk", material)).isZero();
        assertThat(count("pdf_page_cache", material)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE task_id=?", Integer.class, lease.taskId())).isZero();
    }

    @Test void brokenFileHasSanitizedError() throws Exception {
        long material = material("Before damage");
        String key = jdbc.queryForObject("SELECT storage_key FROM material WHERE id=?", String.class, material);
        Files.writeString(storage.open(key), "%PDF-1.7 damaged");
        var lease = start(material, 1, 1, 1);
        assertThatThrownBy(() -> processor.execute(lease)).isInstanceOf(TaskExecutionException.class).hasMessage("PDF_READ_FAILED");
        assertThat(count("material_chunk", material)).isZero();
    }

    @Test void missingFileAndEncryptedReplacementFailWithSafeCompletedSteps() throws Exception {
        long missing = material("Will disappear");
        String missingKey = jdbc.queryForObject("SELECT storage_key FROM material WHERE id=?", String.class, missing);
        storage.deleteQuietly(missingKey);
        var missingLease = start(missing, 1, 1, 1);
        assertThatThrownBy(() -> processor.execute(missingLease)).isInstanceOf(TaskExecutionException.class).hasMessage("PDF_FILE_UNAVAILABLE");
        leases.fail(missingLease, "PDF_FILE_UNAVAILABLE");
        assertThat(tasks.steps(user, Long.toString(missingLease.taskId()), 1, 20).items().getFirst().status()).isEqualTo("FAILED");

        long encrypted = material("Will be protected");
        String encryptedKey = jdbc.queryForObject("SELECT storage_key FROM material WHERE id=?", String.class, encrypted);
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner-secret", "reader-secret", new AccessPermission()));
            document.save(out); Files.write(storage.open(encryptedKey), out.toByteArray());
        }
        var encryptedLease = start(encrypted, 1, 1, 1);
        assertThatThrownBy(() -> processor.execute(encryptedLease)).isInstanceOf(TaskExecutionException.class).hasMessage("PDF_ENCRYPTED");
        var step = tasks.steps(user, Long.toString(encryptedLease.taskId()), 1, 20).items().getFirst();
        assertThat(step.status()).isEqualTo("FAILED");
        assertThat(step.toString()).doesNotContain(encryptedKey, missingKey, "reader-secret");
    }

    private long material(String... texts) throws Exception {
        byte[] data;
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : texts) {
                PDPage page = new PDPage(); pdf.addPage(page);
                if (!text.isEmpty()) try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    stream.newLineAtOffset(20, 700); stream.showText(text); stream.endText();
                }
            }
            pdf.save(out); data = out.toByteArray();
        }
        String key = storage.store(data, "pdf"); files.add(key);
        jdbc.update("INSERT INTO material(user_id,title,material_type,storage_key,page_count,version,status,access_state,created_at,updated_at) VALUES(?,'Title must not become text','PDF',?,?,1,'ACTIVE','NORMAL',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", user, key, texts.length);
        return jdbc.queryForObject("SELECT MAX(id) FROM material WHERE user_id=?", Long.class, user);
    }

    private TaskLeaseService.Lease start(long material, int version, int from, int to) {
        var task = tasks.submit(user, new TaskInput(Long.toString(material), version, UUID.randomUUID().toString(),
            GoalType.REVIEW_CARDS, null, new PageRange(from, to), false, true));
        var lease = leases.claim();
        assertThat(lease.taskId()).isEqualTo(Long.parseLong(task.id()));
        return lease;
    }

    private int count(String table, long material) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE material_id=?", Integer.class, material);
    }
}
