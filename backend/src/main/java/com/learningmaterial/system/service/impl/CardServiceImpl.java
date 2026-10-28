package com.learningmaterial.system.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.CardDtos;
import com.learningmaterial.system.dto.CardLayoutDtos;
import com.learningmaterial.system.dto.CardSourceList;
import com.learningmaterial.system.dto.CardSourceView;
import com.learningmaterial.system.dto.CardView;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.entity.CardRow;
import com.learningmaterial.system.mapper.CardMapper;
import com.learningmaterial.system.mapper.CardLayoutMapper;
import com.learningmaterial.system.mapper.CardOrganizationLockMapper;
import com.learningmaterial.system.mapper.CardRelationMapper;
import com.learningmaterial.system.mapper.FavoriteMapper;
import com.learningmaterial.system.service.CardService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.learningmaterial.system.util.ResourceIdUtils.parseId;

@Service
public class CardServiceImpl implements CardService {
    private final CardMapper cards;
    private final CardLayoutMapper layouts;
    private final CardOrganizationLockMapper organizationLocks;
    private final CardRelationMapper relations;
    private final FavoriteMapper favorites;
    private final ObjectMapper json;
    private final Clock clock;
    public CardServiceImpl(CardMapper cards, CardLayoutMapper layouts, CardOrganizationLockMapper organizationLocks,
        CardRelationMapper relations, FavoriteMapper favorites, ObjectMapper json, Clock clock) {
        this.cards = cards; this.layouts = layouts; this.organizationLocks = organizationLocks;
        this.relations = relations; this.favorites = favorites; this.json = json; this.clock = clock;
    }



    @Override
    @Transactional(readOnly = true)
    public PageView<CardView> list(long userId, int page, int pageSize) {
        return list(userId, null, null, null, false, page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<CardView> list(long userId, String keyword, String subject, String tag,
                                   boolean favoriteOnly, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAGE", "分页参数无效");
        keyword = filter(keyword, 100); subject = filter(subject, 64); tag = filter(tag, 64);
        return new PageView<>(cards.listOwned(userId, keyword, subject, tag, favoriteOnly,
            pageSize, ((long) page - 1) * pageSize).stream().map(this::view).toList(), page, pageSize,
            cards.countOwned(userId, keyword, subject, tag, favoriteOnly));
    }

    @Override
    @Transactional
    public CardView create(long userId, CardDtos.CardInput input) {
        CardRow row = new CardRow();
        row.setUserId(userId);
        row.setTitle(input.title().trim());
        row.setContent(input.content());
        row.setSummary(optional(input.summary()));
        row.setSubject(optional(input.subject()));
        row.setTagsJson(writeTags(input.tags() == null ? List.of() : input.tags()));
        row.setOrigin("MANUAL"); row.setGenerationMode("MANUAL"); row.setIsEdited(false);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        row.setCreatedAt(now); row.setUpdatedAt(now);
        cards.insert(row);
        return view(require(userId, row.getId()));
    }

    @Override
    @Transactional
    public CardView patch(long userId, String id, CardDtos.CardPatch input) {
        if (!input.hasChanges()) throw invalid("至少提供一个修改字段");
        long cardId = parseId(id);
        CardRow row = cards.findOwnedForUpdate(userId, cardId);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "卡片不存在");
        if (!row.getVersion().equals(input.getVersion()))
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "卡片已被修改，请核对后重新编辑", row.getVersion());
        if (input.hasTitle()) row.setTitle(required(input.getTitle(), "标题").trim());
        if (input.hasContent()) row.setContent(required(input.getContent(), "正文"));
        if (input.hasSummary()) row.setSummary(optional(input.getSummary()));
        if (input.hasSubject()) row.setSubject(optional(input.getSubject()));
        if (input.hasTags()) {
            if (input.getTags() == null) throw invalid("标签不能为null");
            row.setTagsJson(writeTags(input.getTags()));
        }
        row.setUpdatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        cards.updateContent(row);
        return view(require(userId, cardId));
    }

    @Override
    @Transactional(readOnly = true)
    public CardView get(long userId, String id) { return view(require(userId, parseId(id))); }

    @Override
    @Transactional
    public void delete(long userId, String id, int version) {
        long cardId;
        try { cardId = parseId(id); }
        catch (ApiException ex) { throw missing(); }
        if (version < 1) throw invalid("卡片版本必须为正整数");
        organizationLocks.ensureRow(userId);
        organizationLocks.lockByUserId(userId);
        // Layout saves take organization -> layout -> cards; keep that order here too.
        var savedLayouts = layouts.listOwnedForUpdate(userId);
        var childIds = cards.listDirectChildIds(userId, cardId);
        var ids = new TreeSet<>(childIds); ids.add(cardId);
        for (long lockedId : ids) {
            CardRow row = cards.findOwnedForUpdate(userId, lockedId);
            if (row == null) throw missing();
            if (lockedId == cardId && row.getVersion() != version)
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "卡片已被修改，请重新读取后核对删除", row.getVersion());
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (long childId : childIds) cards.updateParent(userId, childId, null, now);
        cards.softDelete(userId, cardId, now);
        relations.deleteForCard(userId, cardId);
        favorites.removeCard(userId, cardId);
        for (var layout : savedLayouts) {
            try {
                List<CardLayoutDtos.LayoutItem> items = json.readValue(layout.getItemsJson(), new TypeReference<>() { });
                var remaining = items.stream().filter(item -> !id.equals(item.cardId())).toList();
                if (remaining.size() == items.size()) continue;
                layout.setItemsJson(json.writeValueAsString(remaining));
                layout.setVersion(layout.getVersion() + 1); layout.setUpdatedAt(now);
                layouts.update(layout);
            } catch (JsonProcessingException ex) { throw new IllegalStateException("删除卡片时布局无法更新", ex); }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CardSourceList sources(long userId, String id) {
        long cardId = parseId(id); require(userId, cardId);
        return new CardSourceList(cards.sources(userId, cardId).stream().map(row -> {
            String reason = "DELETED".equals(row.getMaterialStatus()) ? "SOURCE_DELETED"
                : !"NORMAL".equals(row.getAccessState()) ? "SOURCE_RESTRICTED" : null;
            return new CardSourceView(row.getChunkId().toString(), row.getMaterialId().toString(),
                row.getMaterialTitle(), row.getMaterialVersion(), row.getPageNo(),
                reason == null ? row.getQuoteText() : null, reason == null ? row.getContent() : null,
                reason == null, reason, row.getExtractionMethod(), reason == null ? row.getQualityNote() : null);
        }).toList());
    }

    private CardRow require(long userId, long id) {
        CardRow row = cards.findOwned(userId, id);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "卡片不存在");
        return row;
    }

    @Override
    public CardView view(CardRow row) {
        List<String> tags;
        try { tags = json.readValue(row.getTagsJson(), new TypeReference<List<String>>() { }); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("卡片标签无法读取", ex); }
        return new CardView(row.getId().toString(), row.getTitle(), row.getContent(), row.getSummary(),
            tags, row.getSubject(), row.getParentCardId() == null ? null : row.getParentCardId().toString(),
            row.getVersion(), row.getOrigin(), row.getGenerationMode(), Boolean.TRUE.equals(row.getIsEdited()),
            Boolean.TRUE.equals(row.getFavorite()),
            row.getCreatedAt().toInstant(ZoneOffset.UTC), row.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    private String writeTags(List<String> tags) {
        List<String> normalized = tags.stream().map(String::trim).toList();
        if (normalized.stream().map(tag -> tag.toLowerCase(Locale.ROOT)).distinct().count() != normalized.size())
            throw invalid("标签不能重复");
        try { return json.writeValueAsString(normalized); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("卡片标签无法保存", ex); }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field + "不能为空");
        return value;
    }

    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static String filter(String value, int max) {
        String normalized = optional(value);
        if (normalized != null && normalized.length() > max) throw invalid("筛选条件超过长度上限");
        return normalized;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", message);
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "卡片不存在");
    }

}
