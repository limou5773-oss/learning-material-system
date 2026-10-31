package com.learningmaterial.system.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.AiTaskDtos.*;
import com.learningmaterial.system.dto.PersonalMaterialMatch;
import com.learningmaterial.system.entity.AiResultRow;
import com.learningmaterial.system.entity.AiTaskRow;
import com.learningmaterial.system.entity.CardRow;
import com.learningmaterial.system.entity.MaterialChunkRow;
import com.learningmaterial.system.entity.MaterialRow;
import com.learningmaterial.system.entity.TagRow;
import com.learningmaterial.system.mapper.AiResultMapper;
import com.learningmaterial.system.mapper.AiTaskMapper;
import com.learningmaterial.system.mapper.CardMapper;
import com.learningmaterial.system.mapper.MaterialChunkMapper;
import com.learningmaterial.system.mapper.MaterialMapper;
import com.learningmaterial.system.mapper.TagMapper;
import com.learningmaterial.system.service.NotificationService;
import com.learningmaterial.system.service.PersonalMaterialSearchService;
import com.learningmaterial.system.service.TaskConfirmationService;
import com.learningmaterial.system.task.TaskExecutionException;
import com.learningmaterial.system.util.ResourceIdUtils;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskConfirmationServiceImpl implements TaskConfirmationService {
    private final AiTaskMapper tasks;
    private final AiResultMapper results;
    private final MaterialMapper materials;
    private final MaterialChunkMapper chunks;
    private final TagMapper tags;
    private final CardMapper cards;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final PersonalMaterialSearchService related;
    private final NotificationService notifications;
    private final com.learningmaterial.system.service.ContentReviewService reviews;

    public TaskConfirmationServiceImpl(
            AiTaskMapper tasks,
            AiResultMapper results,
            MaterialMapper materials,
            MaterialChunkMapper chunks,
            TagMapper tags,
            CardMapper cards,
            ObjectMapper json,
            Clock clock,
            PlatformTransactionManager transactionManager,
            PersonalMaterialSearchService related,
            NotificationService notifications,
            com.learningmaterial.system.service.ContentReviewService reviews) {
        this.reviews = reviews;
        this.notifications = notifications;
        this.related = related;
        this.tasks = tasks;
        this.results = results;
        this.materials = materials;
        this.chunks = chunks;
        this.tags = tags;
        this.cards = cards;
        this.json = json;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    private record Outcome(ConfirmationResult receipt, ApiException conflict) {}

    @Override
    public ConfirmationResult confirm(long userId, String id, ConfirmationInput input) {
        long taskId = ResourceIdUtils.parseId(id);
        AiTaskRow snapshot = tasks.findOwned(userId, taskId);
        if (snapshot == null) throw notFound();
        Outcome outcome =
                transactions.execute(transaction -> confirmLocked(userId, snapshot, input));
        if (outcome == null) throw new IllegalStateException("确认事务未返回结果");
        // Only an explicit stale-source outcome commits the invalidation. All thrown failures roll
        // back.
        if (outcome.conflict() != null) throw outcome.conflict();
        return outcome.receipt();
    }

    private Outcome confirmLocked(long userId, AiTaskRow snapshot, ConfirmationInput input) {
        // Acquire the whole primary/related material set in ID order before any task/card locks.
        var relatedIds =
                input.relations().stream()
                        .flatMap(
                                relation ->
                                        java.util.stream.Stream.of(
                                                relation.source(), relation.target()))
                        .filter(endpoint -> endpoint.cardId() != null)
                        .map(endpoint -> sourceId(endpoint.cardId()))
                        .distinct()
                        .toList();
        related.lockMaterials(userId, snapshot.getMaterialId(), relatedIds);
        MaterialRow material = materials.findOwnedForUpdate(snapshot.getMaterialId(), userId);
        AiTaskRow task = tasks.findOwnedForUpdate(userId, snapshot.getId());
        if (task == null) throw notFound();
        AiResultRow result = results.findForUpdate(task.getId());
        if (result != null
                && "CONFIRMED".equals(result.getStatus())
                && "SUCCEEDED".equals(task.getStatus()))
            return new Outcome(read(result.getConfirmationJson(), ConfirmationResult.class), null);
        if (!"WAIT_CONFIRM".equals(task.getStatus())
                || Boolean.TRUE.equals(task.getCancelFlag())
                || result == null
                || !"CANDIDATE".equals(result.getStatus()))
            throw conflict("TASK_NOT_CONFIRMABLE", "任务当前不能确认");
        if (material == null)
            throw new ApiException(HttpStatus.CONFLICT, "SOURCE_DELETED", "来源资料已删除");
        if (!"NORMAL".equals(material.getAccessState()))
            throw new ApiException(HttpStatus.FORBIDDEN, "MATERIAL_RESTRICTED", "来源资料暂不可使用");
        if (!result.getVersion().equals(input.resultVersion()))
            throw conflict("RESULT_VERSION_CHANGED", "候选结果版本已变化");
        LocalDateTime now =
                LocalDateTime.ofInstant(
                        clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        if (!material.getVersion().equals(task.getMaterialVersion())
                || !task.getMaterialVersion().equals(input.sourceVersion())) {
            results.invalidate(task.getId(), now);
            if (tasks.failStaleConfirmation(task.getId(), now) != 1)
                throw new IllegalStateException("候选失效时任务状态未更新");
            notifications.recordTaskState(task.getUserId(), task.getId(), "FAILED", now);
            return new Outcome(
                    null,
                    new ApiException(
                            HttpStatus.CONFLICT,
                            "SOURCE_VERSION_CHANGED",
                            "来源资料已变化，请重新生成候选",
                            material.getVersion()));
        }
        CandidateResult original = read(result.getCandidateJson(), CandidateResult.class);
        Map<String, CandidateCard> selected = validateCards(input.cards(), original);
        Map<Long, CardRow> existing = validateRelations(userId, input.relations(), selected);
        validateSources(task, selected, original);
        List<String> materialTags = mergedTags(material.getId(), input);

        Map<String, Long> savedIds = new LinkedHashMap<>();
        Map<String, CandidateCard> originals = new HashMap<>();
        original.cards().forEach(card -> originals.put(card.candidateKey(), card));
        for (CandidateCard card : selected.values()) {
            CardRow row = new CardRow();
            row.setUserId(userId);
            row.setTaskId(task.getId());
            row.setCandidateKey(card.candidateKey());
            row.setTitle(card.title());
            row.setContent(card.content());
            row.setSummary(card.summary());
            row.setSubject(card.subject());
            row.setTagsJson(write(normalizedTags(card.tags())));
            row.setOrigin("AI");
            row.setGenerationMode(original.generationMode());
            row.setIsEdited(!card.equals(originals.get(card.candidateKey())));
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            cards.insert(row);
            savedIds.put(card.candidateKey(), row.getId());
            for (SourceRef ref : card.sources())
                cards.insertSource(
                        row.getId(),
                        sourceId(ref.chunkId()),
                        ref.quoteText(),
                        ref.startOffset(),
                        ref.endOffset(),
                        now);
        }
        for (CandidateCard card : selected.values()) {
            if (card.parentCandidateKey() != null)
                cards.setParent(
                        savedIds.get(card.candidateKey()), savedIds.get(card.parentCandidateKey()));
        }
        for (CandidateRelation relation : input.relations()) {
            long sourceId = endpointId(relation.source(), savedIds, existing);
            long targetId = endpointId(relation.target(), savedIds, existing);
            if ("RELATED".equals(relation.type()) && sourceId > targetId) {
                long swap = sourceId;
                sourceId = targetId;
                targetId = swap;
            }
            cards.insertRelation(
                    userId, sourceId, targetId, relation.type(), relation.evidenceText(), now);
        }

        if (materials.updateConditional(
                        material.getId(),
                        userId,
                        material.getVersion(),
                        material.getTitle(),
                        material.getSubject(),
                        material.getNoteText(),
                        input.summary(),
                        material.getStatus(),
                        now)
                != 1) throw conflict("SOURCE_VERSION_CHANGED", "来源资料版本已变化");
        tags.detachAll(material.getId());
        Set<Long> attached = new HashSet<>();
        for (String name : materialTags) {
            tags.insertOrGet(userId, name, now);
            TagRow tag = tags.findOwnedByName(userId, name);
            if (attached.add(tag.getId())) tags.attach(material.getId(), tag.getId(), now);
        }
        ConfirmationResult receipt =
                new ConfirmationResult(
                        task.getId().toString(),
                        "SUCCEEDED",
                        savedIds.values().stream().map(Object::toString).toList(),
                        material.getVersion() + 1,
                        now.toInstant(ZoneOffset.UTC));
        if (results.confirm(task.getId(), write(receipt), now) != 1
                || tasks.confirm(task.getId(), now) != 1)
            throw conflict("TASK_NOT_CONFIRMABLE", "任务状态已变化");
        reviews.supersedeByMaterial(userId, material.getId(), now);
        return new Outcome(receipt, null);
    }

    private Map<String, CandidateCard> validateCards(
            List<CandidateCard> input, CandidateResult original) {
        Set<String> originalKeys = new HashSet<>();
        original.cards().forEach(card -> originalKeys.add(card.candidateKey()));
        Map<String, CandidateCard> selected = new LinkedHashMap<>();
        for (CandidateCard card : input) {
            if (!originalKeys.contains(card.candidateKey())
                    || selected.putIfAbsent(card.candidateKey(), card) != null)
                throw invalid("INVALID_CANDIDATE_KEY", "请选择原候选中的卡片且不要重复");
            normalizedTags(card.tags());
        }
        for (CandidateCard card : selected.values()) {
            Set<String> path = new HashSet<>();
            CandidateCard current = card;
            while (current != null) {
                if (!path.add(current.candidateKey())) throw invalid("PARENT_CYCLE", "卡片父子关系不能成环");
                String parent = current.parentCandidateKey();
                if (parent != null && !selected.containsKey(parent))
                    throw invalid("INVALID_PARENT", "父卡片必须在本次选中的候选中");
                current = parent == null ? null : selected.get(parent);
            }
        }
        return selected;
    }

    private void validateSources(
            AiTaskRow task, Map<String, CandidateCard> selected, CandidateResult original) {
        Map<String, Set<Long>> allowed = new HashMap<>();
        for (CandidateCard card : original.cards()) {
            Set<Long> ids = new HashSet<>();
            card.sources().forEach(ref -> ids.add(sourceId(ref.chunkId())));
            allowed.put(card.candidateKey(), ids);
        }
        SortedSet<Long> sourceIds = new TreeSet<>();
        for (CandidateCard card : selected.values()) {
            Set<String> seen = new HashSet<>();
            for (SourceRef ref : card.sources()) {
                long chunkId = sourceId(ref.chunkId());
                if (!allowed.get(card.candidateKey()).contains(chunkId)
                        || !seen.add(chunkId + ":" + ref.startOffset() + ":" + ref.endOffset()))
                    throw invalid("INVALID_SOURCE", "来源必须属于原候选且不能重复");
                sourceIds.add(chunkId);
            }
        }
        Map<Long, MaterialChunkRow> locked = new HashMap<>();
        for (long id : sourceIds) locked.put(id, chunks.findForUpdate(id));
        for (CandidateCard card : selected.values())
            for (SourceRef ref : card.sources()) {
                MaterialChunkRow chunk = locked.get(sourceId(ref.chunkId()));
                if (chunk == null
                        || !chunk.getMaterialId().equals(task.getMaterialId())
                        || !chunk.getMaterialVersion().equals(task.getMaterialVersion())
                        || ref.startOffset() < 0
                        || ref.endOffset() <= ref.startOffset()
                        || ref.endOffset() > chunk.getContent().length()
                        || !chunk.getContent()
                                .substring(ref.startOffset(), ref.endOffset())
                                .equals(ref.quoteText()))
                    throw invalid("INVALID_SOURCE", "引用原文或位置无效");
            }
    }

    private Map<Long, CardRow> validateRelations(
            long userId, List<CandidateRelation> relations, Map<String, CandidateCard> selected) {
        SortedSet<Long> ids = new TreeSet<>();
        Set<String> keys = new HashSet<>(), edges = new HashSet<>();
        for (CandidateRelation relation : relations) {
            if (!keys.add(relation.relationKey())) throw invalid("DUPLICATE_RELATION", "关系标识不能重复");
            String source = validateEndpoint(relation.source(), selected, ids);
            String target = validateEndpoint(relation.target(), selected, ids);
            if (source.equals(target)) throw invalid("SELF_RELATION", "不能关联卡片自身");
            if ("RELATED".equals(relation.type()) && source.compareTo(target) > 0) {
                String swap = source;
                source = target;
                target = swap;
            }
            if (!edges.add(source + ":" + target + ":" + relation.type()))
                throw invalid("DUPLICATE_RELATION", "关系不能重复");
            if (relation.source().candidateKey() == null
                    && relation.target().candidateKey() == null)
                throw invalid("INVALID_RELATION", "确认关系必须关联本次选中的卡片");
        }
        Map<Long, CardRow> existing = new HashMap<>();
        for (long id : ids) {
            CardRow card = cards.findOwned(userId, id);
            if (card == null)
                throw new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "卡片不存在");
            existing.put(id, card);
        }
        try {
            // Lock related source materials before their cards, as in candidate publication.
            related.lockAndCheck(
                    userId,
                    existing.values().stream()
                            .map(
                                    card ->
                                            new PersonalMaterialMatch(
                                                    card.getId().toString(),
                                                    card.getVersion(),
                                                    card.getTitle(),
                                                    "",
                                                    false))
                            .toList());
        } catch (TaskExecutionException ex) {
            throw conflict(ex.errorCode(), "关联卡片或来源已变化，请刷新后重试");
        }
        for (CandidateRelation relation : relations) {
            checkEndpointVersion(relation.source(), existing);
            checkEndpointVersion(relation.target(), existing);
        }
        return existing;
    }

    private String validateEndpoint(
            CardEndpoint endpoint, Map<String, CandidateCard> selected, Set<Long> ids) {
        if (endpoint.candidateKey() != null) {
            if (endpoint.cardId() != null
                    || endpoint.cardVersion() != null
                    || !selected.containsKey(endpoint.candidateKey()))
                throw invalid("INVALID_RELATION_ENDPOINT", "关系候选必须在本次选中的卡片中");
            return "candidate:" + endpoint.candidateKey();
        }
        if (endpoint.cardId() == null
                || endpoint.cardVersion() == null
                || endpoint.cardVersion() < 1)
            throw invalid("INVALID_RELATION_ENDPOINT", "既有卡片必须提供ID和版本");
        long id = sourceId(endpoint.cardId());
        ids.add(id);
        return "card:" + id;
    }

    private void checkEndpointVersion(CardEndpoint endpoint, Map<Long, CardRow> existing) {
        if (endpoint.cardId() != null
                && !existing.get(sourceId(endpoint.cardId()))
                        .getVersion()
                        .equals(endpoint.cardVersion()))
            throw conflict("CARD_VERSION_CHANGED", "关联卡片已变化，请刷新后重试");
    }

    private long endpointId(
            CardEndpoint endpoint, Map<String, Long> saved, Map<Long, CardRow> existing) {
        return endpoint.candidateKey() != null
                ? saved.get(endpoint.candidateKey())
                : existing.get(sourceId(endpoint.cardId())).getId();
    }

    private List<String> mergedTags(long materialId, ConfirmationInput input) {
        List<String> names = new ArrayList<>();
        if (input.tagMode() == TagMode.MERGE)
            tags.forMaterial(materialId).forEach(tag -> names.add(tag.getName()));
        for (String name : normalizedTags(input.tags())) {
            if (names.stream().noneMatch(existing -> existing.equalsIgnoreCase(name)))
                names.add(name);
        }
        if (names.size() > 10) throw invalid("TOO_MANY_TAGS", "合并后的资料标签不能超过10个");
        // Deterministic unique-tag index acquisition across confirmations of different materials.
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private List<String> normalizedTags(List<String> input) {
        if (input == null) return List.of();
        Set<String> seen = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (String value : input) {
            if (value == null
                    || value.isBlank()
                    || value.length() > 64
                    || !seen.add(value.trim().toLowerCase(Locale.ROOT)))
                throw invalid("INVALID_TAG", "标签不能为空或重复");
            names.add(value.trim());
        }
        return names;
    }

    private static long sourceId(String id) {
        if (id != null && id.matches("[1-9][0-9]*")) {
            try {
                return Long.parseLong(id);
            } catch (NumberFormatException ignored) {
            }
        }
        throw invalid("INVALID_SOURCE_ID", "来源或关联ID无效");
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("候选结果无法读取", ex);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("确认结果无法保存", ex);
        }
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
    }
}
