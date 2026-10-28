package com.learningmaterial.system.service.impl;

import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.CardRelationDtos;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.entity.CardRelationRow;
import com.learningmaterial.system.entity.CardRow;
import com.learningmaterial.system.mapper.CardMapper;
import com.learningmaterial.system.mapper.CardRelationMapper;
import com.learningmaterial.system.service.CardRelationService;
import com.learningmaterial.system.util.ResourceIdUtils;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.learningmaterial.system.util.ResourceIdUtils.parseId;

@Service
public class CardRelationServiceImpl implements CardRelationService {
    private final CardMapper cards;
    private final CardRelationMapper relations;
    private final Clock clock;
    public CardRelationServiceImpl(CardMapper cards, CardRelationMapper relations, Clock clock) {
        this.cards = cards; this.relations = relations; this.clock = clock;
    }

    @Override
    @Transactional
    public CardRelationDtos.RelationView create(long userId, CardRelationDtos.RelationInput input) {
        long sourceId = inputId(input.sourceCardId()), targetId = inputId(input.targetCardId());
        if (sourceId == targetId) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SELF_RELATION", "不能关联同一卡片");
        CardRow first = cards.findOwnedForUpdate(userId, Math.min(sourceId, targetId));
        if (first == null) throw missing();
        CardRow second = cards.findOwnedForUpdate(userId, Math.max(sourceId, targetId));
        if (second == null) throw missing();
        CardRow source = sourceId == first.getId() ? first : second;
        CardRow target = targetId == first.getId() ? first : second;
        checkVersion(source, input.sourceCardVersion(), "起点");
        checkVersion(target, input.targetCardVersion(), "终点");
        CardRelationRow row = new CardRelationRow(); row.setUserId(userId);
        boolean normalize = "RELATED".equals(input.type()) && sourceId > targetId;
        row.setSourceCardId(normalize ? targetId : sourceId); row.setTargetCardId(normalize ? sourceId : targetId);
        row.setRelationType(input.type()); row.setEvidenceText(input.evidenceText().trim());
        row.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        try { relations.insert(row); }
        catch (DuplicateKeyException ex) { throw new ApiException(HttpStatus.CONFLICT, "RELATION_EXISTS", "该关系已存在，请查看已保存的说明"); }
        return view(row);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<CardRelationDtos.RelationView> list(long userId, String id, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAGE", "分页参数无效");
        Long cardId = id == null ? null : requireOwned(userId, id).getId();
        return new PageView<>(relations.listOwned(userId, cardId, pageSize, ((long) page - 1) * pageSize)
            .stream().map(this::view).toList(), page, pageSize, relations.countOwned(userId, cardId));
    }

    @Override
    @Transactional
    public void delete(long userId, String id) { relations.deleteOwned(userId, ResourceIdUtils.parseId(id)); }

    @Override
    public CardRow requireOwned(long userId, String id) {
        long cardId;
        try { cardId = ResourceIdUtils.parseId(id); }
        catch (ApiException ex) { throw missing(); }
        CardRow row = cards.findOwned(userId, cardId);
        if (row == null) throw missing();
        return row;
    }

    public CardRelationDtos.RelationView view(CardRelationRow row) {
        return new CardRelationDtos.RelationView(row.getId().toString(), row.getSourceCardId().toString(),
            row.getTargetCardId().toString(), row.getRelationType(), row.getEvidenceText());
    }

    private static long inputId(String id) {
        try { return Long.parseLong(id); }
        catch (NumberFormatException ex) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", "卡片ID超出范围"); }
    }
    private static void checkVersion(CardRow row, int version, String endpoint) {
        if (row.getVersion() != version)
            throw new ApiException(HttpStatus.CONFLICT, "CARD_VERSION_CHANGED",
                endpoint + "卡片 #" + row.getId() + " 已修改，请重新读取并核对两端卡片", row.getVersion());
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "卡片不存在"); }
}
