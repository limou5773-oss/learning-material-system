package com.learningmaterial.system.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.material.FileStorage;
import com.learningmaterial.system.material.MaterialMapper;
import com.learningmaterial.system.material.MaterialRow;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.Writer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Reads controlled storage without holding database locks, then publishes immutable page/source data. */
@Service
public class PdfTextExtractionService {
    private static final int MAX_TEXT_UNITS = 60_000;
    private final AiTaskMapper tasks;
    private final MaterialMapper materials;
    private final PdfPageCacheMapper cache;
    private final FileStorage storage;
    private final TaskCandidateService candidates;
    private final TaskLeaseService leases;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public PdfTextExtractionService(AiTaskMapper tasks, MaterialMapper materials, PdfPageCacheMapper cache,
        FileStorage storage, TaskCandidateService candidates, TaskLeaseService leases, ObjectMapper json,
        Clock clock, PlatformTransactionManager transactionManager) {
        this.tasks = tasks; this.materials = materials; this.cache = cache; this.storage = storage;
        this.candidates = candidates; this.leases = leases; this.json = json; this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
    }

    public record Extraction(List<MaterialChunkRow> sources, int totalPages,
                             List<Integer> processedPages, List<String> warnings) { }

    public Extraction extract(TaskLeaseService.Lease lease) {
        AiTaskRow snapshot = tasks.findById(lease.taskId());
        if (snapshot == null || !leases.isCurrent(lease)) return null;
        JsonNode config;
        try { config = json.readTree(snapshot.getConfigJson()); }
        catch (JsonProcessingException ex) { throw new TaskExecutionException("INVALID_TASK_CONFIG"); }
        JsonNode range = config.path("pageRange");
        var arguments = json.createObjectNode().put("materialId", snapshot.getMaterialId().toString())
            .put("materialVersion", snapshot.getMaterialVersion()).set("pageRange", range);
        MockToolProtocol.validate("extract_pdf_text", arguments, snapshot);
        int from = range.path("from").intValue(), to = range.path("to").intValue();
        MaterialRow material = materials.findOwned(snapshot.getMaterialId(), snapshot.getUserId());
        checkMaterial(material, snapshot);
        Map<Integer, PdfPageCacheRow> pages = new TreeMap<>();
        for (PdfPageCacheRow page : cache.findRange(material.getId(), snapshot.getMaterialVersion(), from, to))
            pages.put(page.getPageNo(), page);
        List<PdfPageCacheRow> fresh = new ArrayList<>();
        if (pages.size() != to - from + 1 && !readMissing(material, snapshot, from, to, pages, fresh)) return null;

        int totalPages = pages.values().iterator().next().getTotalPages();
        List<TaskCandidateService.SourceDraft> drafts = new ArrayList<>();
        List<Integer> processed = new ArrayList<>(), needsOcr = new ArrayList<>();
        int textUnits = 0;
        for (PdfPageCacheRow page : pages.values()) {
            if (page.getTotalPages() != totalPages || page.getPageNo() > totalPages)
                throw new TaskExecutionException("PDF_CACHE_CONFLICT");
            textUnits += page.getPageText().length();
            if (textUnits > MAX_TEXT_UNITS) limitExceeded();
            if ("NEEDS_OCR".equals(page.getExtractionStatus())) { needsOcr.add(page.getPageNo()); continue; }
            if (!"TEXT".equals(page.getExtractionStatus()) || page.getPageText().isBlank())
                throw new TaskExecutionException("PDF_CACHE_CONFLICT");
            processed.add(page.getPageNo());
            drafts.addAll(splitPage(page.getPageNo(), page.getPageText()));
            if (drafts.size() > 30) limitExceeded();
        }

        // The material -> task lock order matches deletion, source publication and confirmation.
        List<MaterialChunkRow> saved = transactions.execute(status -> {
            MaterialRow currentMaterial = materials.findOwnedForUpdate(snapshot.getMaterialId(), snapshot.getUserId());
            AiTaskRow task = tasks.findByIdForUpdate(snapshot.getId());
            if (task == null || !leases.isCurrent(lease)) return null;
            if (!Objects.equals(task.getUserId(), snapshot.getUserId())
                || !Objects.equals(task.getMaterialId(), snapshot.getMaterialId())
                || !Objects.equals(task.getMaterialVersion(), snapshot.getMaterialVersion())
                || !Objects.equals(task.getConfigJson(), snapshot.getConfigJson()))
                throw new TaskExecutionException("SOURCE_VERSION_CHANGED");
            checkMaterial(currentMaterial, task);
            if (!Objects.equals(material.getStorageKey(), currentMaterial.getStorageKey())
                || !Objects.equals(material.getFileHash(), currentMaterial.getFileHash()))
                throw new TaskExecutionException("SOURCE_VERSION_CHANGED");
            for (PdfPageCacheRow page : fresh) cache.insertOrReuse(page);
            List<PdfPageCacheRow> persisted = cache.findRange(material.getId(), task.getMaterialVersion(), from, to);
            if (persisted.size() != pages.size()) throw new TaskExecutionException("PDF_CACHE_CONFLICT");
            for (PdfPageCacheRow page : persisted) {
                PdfPageCacheRow expected = pages.get(page.getPageNo());
                if (expected == null || !Objects.equals(page.getPageText(), expected.getPageText())
                    || !Objects.equals(page.getExtractionStatus(), expected.getExtractionStatus())
                    || !Objects.equals(page.getTotalPages(), expected.getTotalPages()))
                    throw new TaskExecutionException("PDF_CACHE_CONFLICT");
            }
            return drafts.isEmpty() ? List.of() : candidates.saveSources(lease, drafts);
        });
        if (saved == null) return null;
        List<String> warnings = needsOcr.isEmpty() ? List.of()
            : List.of("第" + needsOcr.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse("")
                + "页没有可提取文字，可能需要OCR或为空白页；本次未生成这些页的内容。");
        return new Extraction(saved, totalPages, List.copyOf(processed), warnings);
    }

    private boolean readMissing(MaterialRow material, AiTaskRow task, int from, int to,
                             Map<Integer, PdfPageCacheRow> pages, List<PdfPageCacheRow> fresh) {
        try (PDDocument document = Loader.loadPDF(storage.open(material.getStorageKey()).toFile())) {
            if (document.isEncrypted()) throw new TaskExecutionException("PDF_ENCRYPTED");
            if (!document.getCurrentAccessPermission().canExtractContent())
                throw new TaskExecutionException("PDF_EXTRACTION_FORBIDDEN");
            int total = document.getNumberOfPages();
            if (from < 1 || to > total || total < 1 || total > 100)
                throw new TaskExecutionException("INVALID_PAGE_RANGE");
            PDFTextStripper stripper = new PDFTextStripper();
            int remaining = MAX_TEXT_UNITS - pages.values().stream().mapToInt(p -> p.getPageText().length()).sum();
            if (remaining < 0) limitExceeded();
            for (int pageNo = from; pageNo <= to; pageNo++) {
                if (pages.containsKey(pageNo)) continue;
                if (!leases.isCurrent(new TaskLeaseService.Lease(task.getId(), task.getExecutionEpoch()))) return false;
                stripper.setStartPage(pageNo); stripper.setEndPage(pageNo);
                LimitedWriter writer = new LimitedWriter(remaining);
                stripper.writeText(document, writer);
                String text = writer.text();
                boolean empty = text.isBlank();
                if (!empty) remaining -= text.length();
                PdfPageCacheRow page = new PdfPageCacheRow();
                page.setMaterialId(material.getId()); page.setMaterialVersion(task.getMaterialVersion());
                page.setPageNo(pageNo); page.setTotalPages(total);
                page.setExtractionStatus(empty ? "NEEDS_OCR" : "TEXT"); page.setPageText(empty ? "" : text);
                page.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                pages.put(pageNo, page); fresh.add(page);
            }
            return true;
        } catch (InvalidPasswordException ex) { throw new TaskExecutionException("PDF_ENCRYPTED"); }
        catch (ApiException ex) { throw new TaskExecutionException("PDF_FILE_UNAVAILABLE"); }
        catch (IOException ex) { throw new TaskExecutionException("PDF_READ_FAILED"); }
    }

    static List<TaskCandidateService.SourceDraft> splitPage(int pageNo, String text) {
        List<TaskCandidateService.SourceDraft> drafts = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + 2000, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
            drafts.add(new TaskCandidateService.SourceDraft(TaskCandidateService.PDF_CHUNK_BASE + pageNo * 100
                + drafts.size(), text.substring(start, end), "PDF_TEXT", "PDF文字层原文；请对照原文件核查", pageNo));
            if (drafts.size() > 30) limitExceeded();
            start = end;
        }
        return drafts;
    }

    private static void checkMaterial(MaterialRow material, AiTaskRow task) {
        if (material == null || !"NORMAL".equals(material.getAccessState()) || !"PDF".equals(material.getMaterialType()))
            throw new TaskExecutionException("SOURCE_UNAVAILABLE");
        if (!Objects.equals(material.getVersion(), task.getMaterialVersion()))
            throw new TaskExecutionException("SOURCE_VERSION_CHANGED");
    }

    private static void limitExceeded() { throw new TaskExecutionException("PDF_TEXT_LIMIT_EXCEEDED"); }

    private static class LimitedWriter extends Writer {
        private final StringBuilder buffer = new StringBuilder();
        private final int limit;
        LimitedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) {
            if (length > limit - buffer.length()) limitExceeded();
            buffer.append(chars, offset, length);
        }
        @Override public void write(String text, int offset, int length) {
            if (length > limit - buffer.length()) limitExceeded();
            buffer.append(text, offset, offset + length);
        }
        @Override public void flush() { }
        @Override public void close() { }
        String text() { return buffer.toString(); }
    }
}
