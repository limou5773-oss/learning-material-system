package com.learningmaterial.system.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TaskCandidateService {
    public static final int MOCK_METADATA_CHUNK_NO = 1_000_000;
    private final AiTaskMapper tasks;
    private final MaterialMapper materials;
    private final MaterialChunkMapper chunks;
    private final AiResultMapper results;
    private final ObjectMapper json;
    private final Clock clock;

    public TaskCandidateService(AiTaskMapper tasks, MaterialMapper materials, MaterialChunkMapper chunks,
                                AiResultMapper results, ObjectMapper json, Clock clock) {
        this.tasks = tasks;
        this.materials = materials;
        this.chunks = chunks;
        this.results = results;
        this.json = json;
        this.clock = clock;
    }

    public record SourceDraft(int chunkNo, String content, String extractionMethod, String qualityNote) { }

    @Transactional
    public List<MaterialChunkRow> saveSources(TaskLeaseService.Lease lease, List<SourceDraft> drafts) {
        if (drafts == null || drafts.isEmpty() || drafts.size() > 3) invalidCandidate();
        AiTaskRow snapshot = tasks.findById(lease.taskId());
        if (snapshot == null) return null;
        MaterialRow material = materials.findOwnedForUpdate(snapshot.getMaterialId(), snapshot.getUserId());
        AiTaskRow task = tasks.findByIdForUpdate(lease.taskId());
        if (!current(task, lease)) return null;
        checkMaterial(material, task);
        List<MaterialChunkRow> saved = new ArrayList<>();
        for (SourceDraft draft : drafts) {
            int expectedChunkNo = "METADATA".equals(draft.extractionMethod())
                ? MOCK_METADATA_CHUNK_NO : saved.size() + 1;
            if (draft.chunkNo() != expectedChunkNo || draft.content() == null
                || draft.content().isBlank() || draft.content().length() > 500
                || !Set.of("TEXT", "METADATA").contains(draft.extractionMethod())) invalidCandidate();
            MaterialChunkRow row = new MaterialChunkRow();
            row.setMaterialId(task.getMaterialId());
            row.setMaterialVersion(task.getMaterialVersion());
            row.setChunkNo(draft.chunkNo());
            row.setContent(draft.content());
            row.setExtractionMethod(draft.extractionMethod());
            row.setQualityNote(draft.qualityNote());
            row.setCreatedAt(now());
            chunks.insertOrReuse(row);
            MaterialChunkRow persisted = chunks.findVersionChunk(task.getMaterialId(),
                task.getMaterialVersion(), draft.chunkNo());
            if (persisted == null || !persisted.getContent().equals(draft.content())
                || !persisted.getExtractionMethod().equals(draft.extractionMethod())) {
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
        MaterialRow material = materials.findOwnedForUpdate(snapshot.getMaterialId(), snapshot.getUserId());
        AiTaskRow task = tasks.findByIdForUpdate(lease.taskId());
        if (!current(task, lease)) return false;
        checkMaterial(material, task);
        validateCandidate(task, candidate);
        LocalDateTime now = now();
        AiResultRow result = new AiResultRow();
        result.setTaskId(task.getId());
        result.setVersion(1);
        result.setCandidateJson(serialize(candidate));
        result.setValidationJson("{\"valid\":true,\"mode\":\"MOCK\",\"sourceChecked\":true}");
        result.setStatus("CANDIDATE");
        result.setCreatedAt(now);
        result.setUpdatedAt(now);
        results.insert(result);
        if (tasks.markCandidateReady(task.getId(), lease.epoch(), now,
            now.minusSeconds(TaskLeaseService.EXECUTION_SECONDS)) != 1) {
            throw new TaskExecutionException("LEASE_LOST");
        }
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
        if (material == null) throw new ApiException(HttpStatus.NOT_FOUND, "RESULT_NOT_FOUND", "来源资料不存在");
        if (!"NORMAL".equals(material.getAccessState())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MATERIAL_RESTRICTED", "来源资料暂不可查看");
        }
        AiResultRow result = results.findOwned(userId, taskId);
        if (result == null) throw new ApiException(HttpStatus.NOT_FOUND, "RESULT_NOT_FOUND", "候选结果不存在");
        try { return json.readValue(result.getCandidateJson(), CandidateResult.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("候选结果无法读取", ex); }
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
                MaterialChunkRow row = chunks.findSource(task.getMaterialId(), task.getMaterialVersion(), id);
                if (row == null) throw new IllegalStateException("候选来源片段不存在");
                items.add(SourceView.from(row));
            }
        }
        return items;
    }

    private void validateCandidate(AiTaskRow task, CandidateResult candidate) {
        if (candidate == null || !task.getId().toString().equals(candidate.taskId())
            || candidate.resultVersion() != 1 || candidate.sourceVersion() != task.getMaterialVersion()
            || !"MOCK".equals(candidate.generationMode()) || candidate.summary() == null
            || candidate.summary().isBlank() || candidate.summary().length() > 4000
            || candidate.tags() == null || candidate.tags().size() > 10
            || candidate.cards() == null || candidate.cards().isEmpty() || candidate.cards().size() > 30
            || candidate.relations() == null || !candidate.relations().isEmpty()
            || candidate.warnings() == null || candidate.warnings().isEmpty()) invalidCandidate();
        Set<String> keys = new HashSet<>();
        for (var card : candidate.cards()) {
            if (card.candidateKey() == null || !keys.add(card.candidateKey())
                || card.candidateKey().length() > 64 || card.title() == null || card.title().isBlank()
                || card.title().length() > 255 || card.content() == null || card.content().isBlank()
                || card.content().length() > 8000 || card.sources() == null || card.sources().isEmpty()
                || card.sources().size() > 20) invalidCandidate();
            for (SourceRef ref : card.sources()) {
                long id;
                try { id = Long.parseLong(ref.chunkId()); }
                catch (RuntimeException ex) { invalidCandidate(); return; }
                MaterialChunkRow source = chunks.findSource(task.getMaterialId(), task.getMaterialVersion(), id);
                if (source == null || ref.quoteText() == null || ref.quoteText().isBlank()
                    || ref.quoteText().length() > 2000 || ref.startOffset() < 0
                    || ref.endOffset() != ref.startOffset() + ref.quoteText().length()
                    || ref.endOffset() > source.getContent().length()
                    || !source.getContent().substring(ref.startOffset(), ref.endOffset()).equals(ref.quoteText())) {
                    invalidCandidate();
                }
            }
        }
    }

    private void checkMaterial(MaterialRow material, AiTaskRow task) {
        if (material == null || !"NORMAL".equals(material.getAccessState())) {
            throw new TaskExecutionException("SOURCE_UNAVAILABLE");
        }
        if (!material.getVersion().equals(task.getMaterialVersion())) {
            throw new TaskExecutionException("SOURCE_VERSION_CHANGED");
        }
    }

    private boolean current(AiTaskRow task, TaskLeaseService.Lease lease) {
        LocalDateTime now = now();
        return task != null && "RUNNING".equals(task.getStatus())
            && !Boolean.TRUE.equals(task.getCancelFlag()) && task.getExecutionEpoch() == lease.epoch()
            && task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(now)
            && task.getStartedAt() != null
            && task.getStartedAt().plusSeconds(TaskLeaseService.EXECUTION_SECONDS).isAfter(now);
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("候选结果无法序列化", ex); }
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private static void invalidCandidate() { throw new TaskExecutionException("INVALID_CANDIDATE"); }
}
