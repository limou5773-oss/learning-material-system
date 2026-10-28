package com.learningmaterial.system.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.CardLayoutDtos;
import com.learningmaterial.system.entity.CardLayoutRow;
import com.learningmaterial.system.mapper.CardLayoutMapper;
import com.learningmaterial.system.mapper.CardMapper;
import com.learningmaterial.system.mapper.CardOrganizationLockMapper;
import com.learningmaterial.system.service.CardLayoutService;
import com.learningmaterial.system.util.ResourceIdUtils;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.learningmaterial.system.util.ResourceIdUtils.parseId;

@Service
public class CardLayoutServiceImpl implements CardLayoutService {
    private final CardLayoutMapper layouts;
    private final CardMapper cards;
    private final CardOrganizationLockMapper organizationLocks;
    private final ObjectMapper json;
    private final Clock clock;
    public CardLayoutServiceImpl(CardLayoutMapper layouts, CardMapper cards, CardOrganizationLockMapper organizationLocks, ObjectMapper json, Clock clock) {
        this.layouts = layouts; this.cards = cards; this.organizationLocks = organizationLocks; this.json = json; this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public CardLayoutDtos.LayoutView get(long userId, String key) {
        validateKey(key);
        CardLayoutRow row = layouts.findOwned(userId, key);
        if (row == null) return new CardLayoutDtos.LayoutView(key, 0, List.of());
        try { return new CardLayoutDtos.LayoutView(key, row.getVersion(), json.readValue(row.getItemsJson(), new TypeReference<List<CardLayoutDtos.LayoutItem>>() { })); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("卡片布局无法读取", ex); }
    }

    @Override
    @Transactional
    public CardLayoutDtos.LayoutView save(long userId, String key, CardLayoutDtos.LayoutInput input) {
        validateKey(key);
        Set<Long> ids = new TreeSet<>(); Set<Integer> orders = new HashSet<>();
        for (var item : input.items()) {
            if (!ids.add(ResourceIdUtils.parseId(item.cardId())) || !orders.add(item.orderNo()))
                throw invalid("卡片和显示顺序不能重复");
        }
        organizationLocks.ensureRow(userId);
        organizationLocks.lockByUserId(userId);
        CardLayoutRow row = layouts.findOwnedForUpdate(userId, key);
        int version = row == null ? 0 : row.getVersion();
        if (version != input.layoutVersion())
            throw new ApiException(HttpStatus.CONFLICT, "LAYOUT_VERSION_CONFLICT", "布局已被修改，请核对后重新保存", version);
        for (long id : ids) if (cards.findOwnedForUpdate(userId, id) == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "布局引用的卡片不存在");
        boolean creating = row == null;
        if (creating) { row = new CardLayoutRow(); row.setUserId(userId); row.setLayoutKey(key); }
        row.setVersion(version + 1);
        row.setUpdatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        try { row.setItemsJson(json.writeValueAsString(input.items())); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("卡片布局无法保存", ex); }
        if (creating) { row.setCreatedAt(row.getUpdatedAt()); layouts.insert(row); }
        else layouts.update(row);
        return new CardLayoutDtos.LayoutView(key, row.getVersion(), input.items());
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("布局键格式无效");
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", message); }
}
