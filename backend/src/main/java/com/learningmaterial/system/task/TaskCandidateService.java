package com.learningmaterial.system.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.notification.NotificationService;
import com.learningmaterial.system.material.MaterialMapper;
import com.learningmaterial.system.material.MaterialRow;
import com.learningmaterial.system.task.AiTaskDtos.CandidateResult;
import com.learningmaterial.system.task.AiTaskDtos.SourceRef;
import com.learningmaterial.system.task.AiTaskDtos.SourceView;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TaskCandidateService {
    public static final int MOCK_METADATA_CHUNK_NO = 1_000_000;
    public static final int PDF_CHUNK_BASE = 2_000_000;
    public static final int OCR_CHUNK_BASE = 3_000_000;
    public static final int MODEL_NOTE_CHUNK_BASE = 4_000_000;
    private final com.learningmaterial.system.auth.UserMapper users;
    private final AiTaskMapper tasks;
    private final MaterialMapper materials;
    private final MaterialChunkMapper chunks;
    private final AiResultMapper results;
    private final ObjectMapper json;
    private final Clock clock;
    private final NotificationService notifications;

    public TaskCandidateService(
            AiTaskMapper tasks,
            MaterialMapper materials,
            MaterialChunkMapper chunks,
            AiResultMapper results,
            ObjectMapper json,
            Clock clock,
            com.learningmaterial.system.auth.UserMapper users,
            NotificationService notifications) {
        this.notifications = notifications;
        this.users = users;
        this.tasks = tasks;
        this.materials = materials;
        this.chunks = chunks;
        this.results = results;
        this.json = json;
        this.clock = clock;
    }

    public record SourceDraft(
            int chunkNo,
            String content,
            String extractionMethod,
            String qualityNote,
            Integer pageNo) {
        public SourceDraft(
                int chunkNo, String content, String extractionMethod, String qualityNote) {
            this(chunkNo, content, extractionMethod, qualityNote, null);
        }
    }

    @Transactional
    public List<MaterialChunkRow> saveSources(
            TaskLeaseService.Lease lease, List<SourceDraft> drafts) {
        if (drafts == null || drafts.isEmpty() || drafts.size() > 30) invalidCandidate();
        AiTaskRow snapshot = tasks.findById(lease.taskId());
        if (snapshot == null) return null;
        MaterialRow material =
                materials.findOwnedForUpdate(snapshot.getMaterialId(), snapshot.getUserId());
        AiTaskRow task = tasks.findByIdForUpdate(lease.taskId());
        if (!current(task, lease)) return null;
        checkMaterial(material, task);
        List<MaterialChunkRow> saved = new ArrayList<>();
        Map<Integer, Integer> pageParts = new HashMap<>();
        for (SourceDraft draft : drafts) {
            boolean ocr = "OCR".equals(draft.extractionMethod());
            boolean pdf =
                    "PDF_TEXT".equals(draft.extractionMethod())
                            || ocr && "PDF".equals(material.getMaterialType());
            boolean modelNote =
                    "TEXT".equals(draft.extractionMethod())
                            && draft.chunkNo() >= MODEL_NOTE_CHUNK_BASE;
            boolean longSource = pdf || ocr || modelNote;
            if (pdf
                    && (!"PDF".equals(material.getMaterialType())
                            || draft.pageNo() == null
                            || draft.pageNo() < 1
                            || draft.pageNo() > 100)) invalidCandidate();
            if (ocr && !Set.of("PDF", "IMAGE").contains(material.getMaterialType()))
                invalidCandidate();
            if (!pdf
                    && !ocr
                    && !("TEXT".equals(draft.extractionMethod())
                            && "NOTE".equals(material.getMaterialType()))
                    && !("METADATA".equals(draft.extractionMethod())
                            && "IMAGE".equals(material.getMaterialType()))) invalidCandidate();
            if (pdf) {
                try {
                    var range = json.readTree(task.getConfigJson()).path("pageRange");
                    if (draft.pageNo() < range.path("from").asInt()
                            || draft.pageNo() > range.path("to").asInt()) invalidCandidate();
                } catch (JsonProcessingException ex) {
                    throw new TaskExecutionException("INVALID_TASK_CONFIG");
                }
            }
            int pageKey = pdf ? draft.pageNo() : 0;
            int part = longSource ? pageParts.merge(pageKey, 1, Integer::sum) - 1 : 0;
            int expectedChunkNo =
                    ocr
                            ? OCR_CHUNK_BASE + pageKey * 100 + part
                            : pdf
                                    ? PDF_CHUNK_BASE + pageKey * 100 + part
                                    : modelNote
                                            ? MODEL_NOTE_CHUNK_BASE + saved.size() + 1
                                            : "METADATA".equals(draft.extractionMethod())
                                                    ? MOCK_METADATA_CHUNK_NO
                                                    : saved.size() + 1;
            if (draft.chunkNo() != expectedChunkNo
                    || draft.content() == null
                    || draft.content().isEmpty()
                    || (!longSource && draft.content().isBlank())
                    || draft.content().length() > (longSource ? 2000 : 500)
                    || (!pdf && draft.pageNo() != null)
                    || (!longSource && drafts.size() > 3)
                    || !Set.of("TEXT", "METADATA", "PDF_TEXT", "OCR")
                            .contains(draft.extractionMethod())) invalidCandidate();
            MaterialChunkRow row = new MaterialChunkRow();
            row.setMaterialId(task.getMaterialId());
            row.setMaterialVersion(task.getMaterialVersion());
            row.setChunkNo(draft.chunkNo());
            row.setPageNo(draft.pageNo());
            row.setContent(draft.content());
            row.setExtractionMethod(draft.extractionMethod());
            row.setQualityNote(draft.qualityNote());
            row.setCreatedAt(now());
            chunks.insertOrReuse(row);
            MaterialChunkRow persisted =
                    chunks.findVersionChunk(
                            task.getMaterialId(), task.getMaterialVersion(), draft.chunkNo());
            if (persisted == null
                    || !persisted.getContent().equals(draft.content())
                    || !persisted.getExtractionMethod().equals(draft.extractionMethod())
                    || !Objects.equals(persisted.getPageNo(), draft.pageNo())) {
                throw new TaskExecutionException("SOURCE_CHUNK_CONFLICT");
            }
            saved.add(persisted);
        }
        return saved;
    }

    @Transactional
    public boolean finish(TaskLeaseService.Lease lease, CandidateResult candidate) {
        AiTaskRow snapshot = tasks.findById(lease.taskId());
        if (snapshot == null) return false;
        MaterialRow material =
                materials.findOwnedForUpdate(snapshot.getMaterialId(), snapshot.getUserId());
        AiTaskRow task = tasks.findByIdForUpdate(lease.taskId());
        if (!current(task, lease)) return false;
        checkMaterial(material, task);
        validateCandidate(task, candidate);
        LocalDateTime now = now();
        AiResultRow result = new AiResultRow();
        result.setTaskId(task.getId());
        result.setVersion(1);
        result.setCandidateJson(serialize(candidate));
        result.setValidationJson(
                "{\"valid\":true,\"mode\":\""
                        + candidate.generationMode()
                        + "\",\"sourceChecked\":true}");
        result.setStatus("CANDIDATE");
        result.setCreatedAt(now);
        result.setUpdatedAt(now);
        results.insert(result);
        if (tasks.markCandidateReady(
                        task.getId(),
                        lease.epoch(),
                        now,
                        now.minusSeconds(TaskLeaseService.EXECUTION_SECONDS))
                != 1) {
            throw new TaskExecutionException("LEASE_LOST");
        }
        notifications.recordTaskState(task.getUserId(), task.getId(), "WAIT_CONFIRM", now);
        return true;
    }

    @Transactional(readOnly = true)
    public CandidateResult getResult(long userId, long taskId) {
        AiTaskRow task = tasks.findOwned(userId, taskId);
        if (task == null) throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
        if (!Set.of("WAIT_CONFIRM", "SUCCEEDED").contains(task.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESULT_NOT_FOUND", "候选结果尚不可查看");
        }
        MaterialRow material = materials.findOwned(task.getMaterialId(), userId);
        if (material == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "RESULT_NOT_FOUND", "来源资料不存在");
        if (!"NORMAL".equals(material.getAccessState())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MATERIAL_RESTRICTED", "来源资料暂不可查看");
        }
        AiResultRow result = results.findOwned(userId, taskId);
        if (result == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "RESULT_NOT_FOUND", "候选结果不存在");
        try {
            return json.readValue(result.getCandidateJson(), CandidateResult.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("候选结果无法读取", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<SourceView> getSources(long userId, long taskId) {
        CandidateResult result = getResult(userId, taskId);
        AiTaskRow task = tasks.findOwned(userId, taskId);
        Set<String> seen = new HashSet<>();
        List<SourceView> items = new ArrayList<>();
        for (var card : result.cards()) {
            for (SourceRef ref : card.sources()) {
                if (!seen.add(ref.chunkId())) continue;
                long id = Long.parseLong(ref.chunkId());
                MaterialChunkRow row =
                        chunks.findSource(task.getMaterialId(), task.getMaterialVersion(), id);
                if (row == null) throw new IllegalStateException("候选来源片段不存在");
                items.add(SourceView.from(row));
            }
        }
        return items;
    }

    private void validateCandidate(AiTaskRow task, CandidateResult candidate) {
        if (candidate == null
                || !task.getId().toString().equals(candidate.taskId())
                || candidate.resultVersion() != 1
                || candidate.sourceVersion() != task.getMaterialVersion()
                || candidate.generationMode() == null
                || !Set.of("MOCK", "MODEL").contains(candidate.generationMode())
                || candidate.summary() == null
                || candidate.summary().isBlank()
                || candidate.summary().length() > 4000
                || candidate.tags() == null
                || candidate.tags().size() > 10
                || candidate.cards() == null
                || candidate.cards().isEmpty()
                || candidate.cards().size() > 30
                || candidate.relations() == null
                || candidate.relations().size() > 10
                || candidate.warnings() == null
                || candidate.warnings().isEmpty()) invalidCandidate();
        if (!validTags(candidate.tags()) || candidate.processedPages() == null) invalidCandidate();
        if ("MODEL".equals(candidate.generationMode())
                && (candidate.generationInfo() == null
                        || !safeIdentifier(candidate.generationInfo().provider(), 96)
                        || !safeIdentifier(candidate.generationInfo().model(), 128)
                        || !safeIdentifier(candidate.generationInfo().promptVersion(), 64)))
            invalidCandidate();
        Set<String> keys = new HashSet<>();
        for (var card : candidate.cards()) {
            if (card == null
                    || card.candidateKey() == null
                    || !keys.add(card.candidateKey())
                    || card.candidateKey().isBlank()
                    || card.candidateKey().length() > 64
                    || card.title() == null
                    || card.title().isBlank()
                    || card.title().length() > 255
                    || card.content() == null
                    || card.content().isBlank()
                    || card.content().length() > 8000
                    || card.sources() == null
                    || card.sources().isEmpty()
                    || card.sources().size() > 20
                    || card.parentCandidateKey() != null
                    || card.summary() != null && card.summary().length() > 2000
                    || card.subject() != null && card.subject().length() > 64
                    || !validTags(card.tags())) invalidCandidate();
            for (SourceRef ref : card.sources()) {
                long id;
                try {
                    id = Long.parseLong(ref.chunkId());
                } catch (RuntimeException ex) {
                    invalidCandidate();
                    return;
                }
                MaterialChunkRow source =
                        chunks.findSource(task.getMaterialId(), task.getMaterialVersion(), id);
                if (source == null
                        || ref.startOffset() == null
                        || ref.endOffset() == null
                        || ref.quoteText() == null
                        || ref.quoteText().isBlank()
                        || ref.quoteText().length() > 2000
                        || ref.startOffset() < 0
                        || ref.startOffset() > source.getContent().length()
                        || ref.endOffset() < ref.startOffset()
                        || ref.endOffset() != ref.startOffset() + ref.quoteText().length()
                        || ref.endOffset() > source.getContent().length()
                        || !source.getContent()
                                .substring(ref.startOffset(), ref.endOffset())
                                .equals(ref.quoteText())) {
                    invalidCandidate();
                }
            }
        }
        if (!candidate.relations().isEmpty()) {
            try {
                var config = json.readTree(task.getConfigJson());
                if (!"agent".equals(config.path("processorMode").asText())
                        || !config.path("allowRelatedSearch").asBoolean()
                        || !"MODEL".equals(candidate.generationMode())) invalidCandidate();
            } catch (JsonProcessingException ex) {
                throw new TaskExecutionException("INVALID_TASK_CONFIG");
            }
            Set<String> edges = new HashSet<>(), relationKeys = new HashSet<>();
            for (var relation : candidate.relations()) {
                if (relation == null
                        || relation.relationKey() == null
                        || !relationKeys.add(relation.relationKey())
                        || relation.source() == null
                        || relation.target() == null
                        || !keys.contains(relation.source().candidateKey())
                        || relation.source().cardId() != null
                        || relation.source().cardVersion() != null
                        || relation.target().candidateKey() != null
                        || relation.target().cardId() == null
                        || !relation.target().cardId().matches("[1-9][0-9]*")
                        || relation.target().cardVersion() == null
                        || relation.target().cardVersion() < 1
                        || !"RELATED".equals(relation.type())
                        || relation.evidenceText() == null
                        || relation.evidenceText().isBlank()
                        || relation.evidenceText().length() > 2000
                        || !edges.add(
                                relation.source().candidateKey()
                                        + ":"
                                        + relation.target().cardId())) invalidCandidate();
            }
        }
    }

    private static boolean validTags(List<String> tags) {
        return tags != null
                && tags.size() <= 10
                && tags.stream().allMatch(t -> t != null && !t.isBlank() && t.length() <= 64);
    }

    private static boolean safeIdentifier(String value, int limit) {
        return value != null && value.length() <= limit && value.matches("[A-Za-z0-9_.:-]+");
    }

    private void checkMaterial(MaterialRow material, AiTaskRow task) {
        var user = users.findById(task.getUserId());
        if (user == null || !"ENABLED".equals(user.getStatus()))
            throw new TaskExecutionException("SOURCE_UNAVAILABLE");
        if (material == null
                || !"NORMAL".equals(material.getAccessState())
                || !Objects.equals(material.getId(), task.getMaterialId())
                || !Objects.equals(material.getUserId(), task.getUserId())) {
            throw new TaskExecutionException("SOURCE_UNAVAILABLE");
        }
        if (!material.getVersion().equals(task.getMaterialVersion())) {
            throw new TaskExecutionException("SOURCE_VERSION_CHANGED");
        }
        try {
            if (!material.getMaterialType()
                    .equals(
                            json.readTree(task.getConfigJson())
                                    .path("inputSnapshot")
                                    .path("type")
                                    .asText()))
                throw new TaskExecutionException("SOURCE_UNAVAILABLE");
        } catch (JsonProcessingException ex) {
            throw new TaskExecutionException("INVALID_TASK_CONFIG");
        }
    }

    private boolean current(AiTaskRow task, TaskLeaseService.Lease lease) {
        LocalDateTime now = now();
        return task != null
                && "RUNNING".equals(task.getStatus())
                && !Boolean.TRUE.equals(task.getCancelFlag())
                && task.getExecutionEpoch() == lease.epoch()
                && task.getLeaseUntil() != null
                && task.getLeaseUntil().isAfter(now)
                && task.getStartedAt() != null
                && task.getStartedAt().plusSeconds(TaskLeaseService.EXECUTION_SECONDS).isAfter(now);
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("候选结果无法序列化", ex);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void invalidCandidate() {
        throw new TaskExecutionException("INVALID_CANDIDATE");
    }
}
