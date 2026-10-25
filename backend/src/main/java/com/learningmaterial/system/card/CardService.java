package com.learningmaterial.system.card;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class CardService {
    private final CardMapper cards;
    private final ObjectMapper json;
    public CardService(CardMapper cards, ObjectMapper json) { this.cards = cards; this.json = json; }

    public record CardView(String id, String title, String content, String summary, List<String> tags,
                           String subject, String parentCardId, int version, String origin,
                           String generationMode, boolean isEdited, Instant createdAt, Instant updatedAt) { }
    public record CardSourceView(String chunkId, String materialId, String materialTitle,
                                 int materialVersion, Integer pageNo, String quoteText, String content,
                                 boolean isAvailable, String unavailableReason, String extractionMethod, String qualityNote) { }
    public record SourceList(List<CardSourceView> items) { }

    @Transactional(readOnly = true)
    public PageView<CardView> list(long userId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAGE", "分页参数无效");
        return new PageView<>(cards.listOwned(userId, pageSize, ((long) page - 1) * pageSize)
            .stream().map(this::view).toList(), page, pageSize, cards.countOwned(userId));
    }

    @Transactional(readOnly = true)
    public CardView get(long userId, String id) { return view(require(userId, parseId(id))); }

    @Transactional(readOnly = true)
    public SourceList sources(long userId, String id) {
        long cardId = parseId(id); require(userId, cardId);
        return new SourceList(cards.sources(userId, cardId).stream().map(row -> {
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

    private CardView view(CardRow row) {
        List<String> tags;
        try { tags = json.readValue(row.getTagsJson(), new TypeReference<List<String>>() { }); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("卡片标签无法读取", ex); }
        return new CardView(row.getId().toString(), row.getTitle(), row.getContent(), row.getSummary(),
            tags, row.getSubject(), row.getParentCardId() == null ? null : row.getParentCardId().toString(),
            row.getVersion(), row.getOrigin(), row.getGenerationMode(), Boolean.TRUE.equals(row.getIsEdited()),
            row.getCreatedAt().toInstant(ZoneOffset.UTC), row.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    public static long parseId(String id) {
        if (id != null && id.matches("[1-9][0-9]*")) {
            try { return Long.parseLong(id); } catch (NumberFormatException ignored) { }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "ID格式错误");
    }
}
