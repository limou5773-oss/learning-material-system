package com.learningmaterial.system.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.auth.UserMapper;
import com.learningmaterial.system.material.MaterialMapper;
import com.learningmaterial.system.material.MaterialRow;
import com.learningmaterial.system.ocr.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/**
 * Bounded OCR orchestration. Image preparation and provider I/O never run under a DB transaction.
 */
@Service
public class OcrExtractionService {
    private final AiTaskMapper tasks;
    private final UserMapper users;
    private final MaterialMapper materials;
    private final OcrPageCacheMapper cache;
    private final OcrAttemptMapper attempts;
    private final TaskCandidateService candidates;
    private final TaskLeaseService leases;
    private final OcrImagePreparation images;
    private final OcrClient client;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public OcrExtractionService(
            AiTaskMapper tasks,
            MaterialMapper materials,
            OcrPageCacheMapper cache,
            OcrAttemptMapper attempts,
            TaskCandidateService candidates,
            TaskLeaseService leases,
            OcrImagePreparation images,
            OcrClient client,
            ObjectMapper json,
            Clock clock,
            PlatformTransactionManager manager,
            UserMapper users) {
        this.users = users;
        this.tasks = tasks;
        this.materials = materials;
        this.cache = cache;
        this.attempts = attempts;
        this.candidates = candidates;
        this.leases = leases;
        this.images = images;
        this.client = client;
        this.json = json;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
    }

    public record Outcome(
            PdfTextExtractionService.Extraction extraction, int dispatched, int cacheHits) {
        public String summary() {
            return "OCR可能已发送" + dispatched + "次，命中缓存" + cacheHits + "页；已核验来源，未记录识别正文。";
        }
    }

    public String attemptSummary(TaskLeaseService.Lease lease) {
        return "OCR可能已发送" + attempts.countDispatched(lease.taskId(), lease.epoch()) + "次；识别未完成。";
    }

    public Outcome extract(TaskLeaseService.Lease lease, PdfTextExtractionService.Extraction pdf) {
        AiTaskRow snapshot = tasks.findById(lease.taskId());
        if (snapshot == null || !leases.isCurrent(lease)) return null;
        MaterialRow material = materials.findOwned(snapshot.getMaterialId(), snapshot.getUserId());
        checkMaterial(material, snapshot, null);
        boolean isPdf = "PDF".equals(material.getMaterialType());
        if (isPdf && pdf == null) throw new TaskExecutionException("INVALID_TASK_CONFIG");
        List<Integer> selected = isPdf ? pdf.needsOcrPages() : List.of(0);
        String provider = client.providerKey();
        if (provider == null || !provider.matches("[A-Za-z0-9_:-]{1,96}"))
            throw new TaskExecutionException("OCR_PROVIDER_INVALID");
        Map<Integer, OcrPageCacheRow> pages = new TreeMap<>();
        List<OcrPageCacheRow> fresh = new ArrayList<>();
        Map<Integer, OcrAttemptRow> pending = new HashMap<>();
        int hits = 0;
        try {
            for (int page : selected) {
                OcrPageCacheRow existing =
                        cache.find(material.getId(), snapshot.getMaterialVersion(), page);
                if (existing != null) {
                    checkCache(existing, provider);
                    pages.put(page, existing);
                    hits++;
                    continue;
                }
                if (!leases.isCurrent(lease)) {
                    unknown(snapshot, "LEASE_LOST");
                    return null;
                }
                // A controlled path is resolved only by FileStorage inside preparation.
                byte[] bytes = images.prepare(material, page);
                while (true) {
                    OcrAttemptRow attempt = beginAttempt(lease, snapshot, material, page, provider);
                    if (attempt == null) {
                        unknown(snapshot, "LEASE_LOST");
                        return null;
                    }
                    try {
                        OcrClient.Result result = client.recognize(bytes);
                        if (result == null
                                || result.text() == null
                                || result.confidence() != null
                                        && (!Double.isFinite(result.confidence())
                                                || result.confidence() < 0
                                                || result.confidence() > 1))
                            throw new OcrException("OCR_RESPONSE_INVALID", false, true);
                        attempt.setRequestId(safeRequestId(result.requestId()));
                        if (!validateCurrent(lease, snapshot, material)) {
                            unknown(snapshot, "LEASE_LOST");
                            return null;
                        }
                        OcrPageCacheRow row = new OcrPageCacheRow();
                        row.setMaterialId(material.getId());
                        row.setMaterialVersion(snapshot.getMaterialVersion());
                        row.setPageNo(page);
                        row.setProviderKey(provider);
                        row.setPageText(result.text().isBlank() ? "" : result.text());
                        row.setExtractionStatus(row.getPageText().isEmpty() ? "EMPTY" : "TEXT");
                        row.setConfidence(result.confidence());
                        row.setRequestId(attempt.getRequestId());
                        row.setCreatedAt(now());
                        pages.put(page, row);
                        fresh.add(row);
                        pending.put(page, attempt);
                        break;
                    } catch (OcrException ex) {
                        boolean current;
                        try {
                            current = validateCurrent(lease, snapshot, material);
                        } catch (RuntimeException stateError) {
                            complete(attempt, "UNKNOWN", ex.dispatched(), "SOURCE_CHANGED");
                            throw stateError;
                        }
                        complete(
                                attempt,
                                current ? "FAILED" : "UNKNOWN",
                                ex.dispatched(),
                                safeCode(ex.code()));
                        if (!current) return null;
                        if (!ex.retryable() || attempt.getAttemptNo() >= 2)
                            throw new TaskExecutionException(safeCode(ex.code()));
                    }
                }
            }
            List<MaterialChunkRow> prior = isPdf ? pdf.sources() : List.of();
            List<TaskCandidateService.SourceDraft> drafts = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            SortedSet<Integer> processed = new TreeSet<>(isPdf ? pdf.processedPages() : List.of());
            int units = prior.stream().mapToInt(s -> s.getContent().length()).sum();
            List<Integer> empty = new ArrayList<>();
            for (var entry : pages.entrySet()) {
                var row = entry.getValue();
                checkCache(row, provider);
                if ("EMPTY".equals(row.getExtractionStatus())) {
                    empty.add(entry.getKey());
                    continue;
                }
                units += row.getPageText().length();
                if (units > 60_000) limit();
                List<TaskCandidateService.SourceDraft> parts =
                        splitPage(entry.getKey(), isPdf, row.getPageText(), qualityNote(row));
                drafts.addAll(parts);
                if (prior.size() + drafts.size() > 30) limit();
                if (isPdf) processed.add(entry.getKey());
                if (row.getConfidence() == null)
                    warnings.add(label(entry.getKey(), isPdf) + "OCR置信度未知，请人工核对。");
                else if (row.getConfidence() < .8)
                    warnings.add(label(entry.getKey(), isPdf) + "OCR置信度较低，请人工核对。");
            }
            if (!empty.isEmpty())
                warnings.add(
                        isPdf
                                ? "第"
                                        + String.join(
                                                "、", empty.stream().map(String::valueOf).toList())
                                        + "页OCR没有识别到文字，未生成这些页的内容。"
                                : "图片OCR没有识别到文字，未生成内容。");
            List<MaterialChunkRow> saved =
                    transactions.execute(
                            status -> {
                                if (!lockedCurrent(lease, snapshot, material)) return null;
                                for (OcrPageCacheRow row : fresh) cache.insertOrReuse(row);
                                for (var entry : pages.entrySet()) {
                                    OcrPageCacheRow persisted =
                                            cache.find(
                                                    material.getId(),
                                                    snapshot.getMaterialVersion(),
                                                    entry.getKey());
                                    if (persisted == null
                                            || !sameCache(persisted, entry.getValue()))
                                        throw new TaskExecutionException("OCR_CACHE_CONFLICT");
                                }
                                List<MaterialChunkRow> result = new ArrayList<>(prior);
                                if (!drafts.isEmpty()) {
                                    List<MaterialChunkRow> sources =
                                            candidates.saveSources(lease, drafts);
                                    if (sources == null)
                                        throw new TaskExecutionException("LEASE_LOST");
                                    result.addAll(sources);
                                }
                                for (OcrAttemptRow attempt : pending.values())
                                    complete(attempt, "SUCCESS", true, null);
                                return result;
                            });
            if (saved == null) {
                unknown(snapshot, "LEASE_LOST");
                return null;
            }
            if (saved.isEmpty()) throw new TaskExecutionException("OCR_EMPTY_RESULT");
            return new Outcome(
                    new PdfTextExtractionService.Extraction(
                            List.copyOf(saved),
                            isPdf ? pdf.totalPages() : 0,
                            List.copyOf(processed),
                            List.copyOf(warnings),
                            List.of()),
                    attempts.countDispatched(lease.taskId(), lease.epoch()),
                    hits);
        } catch (RuntimeException ex) {
            unknown(snapshot, "OCR_PUBLICATION_INCOMPLETE");
            throw ex;
        }
    }

    private OcrAttemptRow beginAttempt(
            TaskLeaseService.Lease lease,
            AiTaskRow snapshot,
            MaterialRow original,
            int page,
            String provider) {
        return transactions.execute(
                status -> {
                    if (!lockedCurrent(lease, snapshot, original)) return null;
                    int previous = attempts.countPage(lease.taskId(), page);
                    if (previous >= 2 || attempts.countTask(lease.taskId()) >= 20)
                        throw new TaskExecutionException("OCR_ATTEMPT_LIMIT");
                    OcrAttemptRow row = new OcrAttemptRow();
                    row.setTaskId(lease.taskId());
                    row.setExecutionEpoch(lease.epoch());
                    row.setPageNo(page);
                    row.setAttemptNo(previous + 1);
                    row.setProviderKey(provider);
                    row.setStartedAt(now());
                    attempts.insert(row);
                    return row;
                });
    }

    private boolean validateCurrent(
            TaskLeaseService.Lease lease, AiTaskRow snapshot, MaterialRow original) {
        return Boolean.TRUE.equals(
                transactions.execute(status -> lockedCurrent(lease, snapshot, original)));
    }

    /** All publication/request guards follow the material -> task lock order. */
    private boolean lockedCurrent(
            TaskLeaseService.Lease lease, AiTaskRow snapshot, MaterialRow original) {
        MaterialRow material =
                materials.findOwnedForUpdate(snapshot.getMaterialId(), snapshot.getUserId());
        AiTaskRow current = tasks.findByIdForUpdate(snapshot.getId());
        if (current == null || !leases.isCurrent(lease)) return false;
        if (!Objects.equals(current.getUserId(), snapshot.getUserId())
                || !Objects.equals(current.getMaterialId(), snapshot.getMaterialId())
                || !Objects.equals(current.getMaterialVersion(), snapshot.getMaterialVersion())
                || !Objects.equals(current.getConfigJson(), snapshot.getConfigJson()))
            throw new TaskExecutionException("SOURCE_VERSION_CHANGED");
        checkMaterial(material, current, original);
        return true;
    }

    private void checkMaterial(MaterialRow material, AiTaskRow task, MaterialRow original) {
        var user = users.findById(task.getUserId());
        if (user == null || !"ENABLED".equals(user.getStatus()))
            throw new TaskExecutionException("SOURCE_UNAVAILABLE");
        if (material == null
                || !"NORMAL".equals(material.getAccessState())
                || !Objects.equals(material.getId(), task.getMaterialId())
                || !Objects.equals(material.getUserId(), task.getUserId()))
            throw new TaskExecutionException("SOURCE_UNAVAILABLE");
        if (!Objects.equals(material.getVersion(), task.getMaterialVersion()))
            throw new TaskExecutionException("SOURCE_VERSION_CHANGED");
        try {
            var input = json.readTree(task.getConfigJson()).path("inputSnapshot");
            String type = input.path("type").asText();
            if (!Set.of("PDF", "IMAGE").contains(type) || !type.equals(material.getMaterialType()))
                throw new TaskExecutionException("SOURCE_UNAVAILABLE");
            if (!Objects.equals(input.path("storageKey").asText(null), material.getStorageKey())
                    || !Objects.equals(input.path("fileHash").asText(null), material.getFileHash()))
                throw new TaskExecutionException("SOURCE_VERSION_CHANGED");
        } catch (JsonProcessingException ex) {
            throw new TaskExecutionException("INVALID_TASK_CONFIG");
        }
        if (original != null
                && (!Objects.equals(material.getStorageKey(), original.getStorageKey())
                        || !Objects.equals(material.getFileHash(), original.getFileHash())))
            throw new TaskExecutionException("SOURCE_VERSION_CHANGED");
    }

    private void complete(OcrAttemptRow attempt, String status, boolean dispatched, String code) {
        attempt.setDispatched(dispatched);
        attempt.setStatus(status);
        attempt.setErrorCode(code);
        attempt.setFinishedAt(now());
        attempt.setDurationMs(
                Math.max(
                        0,
                        Duration.between(attempt.getStartedAt(), attempt.getFinishedAt())
                                .toMillis()));
        attempts.complete(attempt);
    }

    private void unknown(AiTaskRow task, String code) {
        attempts.markEpochUnknown(task.getId(), task.getExecutionEpoch(), code, now());
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "OCR_REQUEST_FAILED";
    }

    private static String safeRequestId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,128}") ? id : null;
    }

    private static String qualityNote(OcrPageCacheRow row) {
        return row.getConfidence() == null
                ? "OCR原文；置信度未知，请人工核对"
                : row.getConfidence() < .8 ? "OCR原文；置信度较低，请人工核对" : "OCR原文；请对照原文件核查";
    }

    private static String label(int page, boolean pdf) {
        return pdf ? "第" + page + "页" : "图片";
    }

    private static void checkCache(OcrPageCacheRow row, String provider) {
        if (!provider.equals(row.getProviderKey())
                || row.getPageText() == null
                || !("TEXT".equals(row.getExtractionStatus()) && !row.getPageText().isBlank()
                        || "EMPTY".equals(row.getExtractionStatus()) && row.getPageText().isEmpty())
                || row.getConfidence() != null
                        && (!Double.isFinite(row.getConfidence())
                                || row.getConfidence() < 0
                                || row.getConfidence() > 1))
            throw new TaskExecutionException("OCR_CACHE_CONFLICT");
    }

    private static boolean sameCache(OcrPageCacheRow a, OcrPageCacheRow b) {
        return Objects.equals(a.getProviderKey(), b.getProviderKey())
                && Objects.equals(a.getPageText(), b.getPageText())
                && Objects.equals(a.getExtractionStatus(), b.getExtractionStatus())
                && Objects.equals(a.getConfidence(), b.getConfidence());
    }

    static List<TaskCandidateService.SourceDraft> splitPage(
            int page, boolean pdf, String text, String quality) {
        if (text.length() > 60_000) limit();
        List<TaskCandidateService.SourceDraft> result = new ArrayList<>();
        for (int start = 0; start < text.length(); ) {
            int end = Math.min(start + 2000, text.length());
            if (end < text.length()
                    && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) end--;
            result.add(
                    new TaskCandidateService.SourceDraft(
                            TaskCandidateService.OCR_CHUNK_BASE + page * 100 + result.size(),
                            text.substring(start, end),
                            "OCR",
                            quality,
                            pdf ? page : null));
            if (result.size() > 30) limit();
            start = end;
        }
        return result;
    }

    private static void limit() {
        throw new TaskExecutionException("OCR_TEXT_LIMIT_EXCEEDED");
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
