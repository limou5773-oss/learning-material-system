package com.learningmaterial.system.service.impl;

import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.dto.FavoriteView;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.mapper.CardMapper;
import com.learningmaterial.system.mapper.FavoriteMapper;
import com.learningmaterial.system.mapper.MaterialMapper;
import com.learningmaterial.system.service.FavoriteService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteServiceImpl implements FavoriteService {
    private final FavoriteMapper favorites;
    private final MaterialMapper materials;
    private final CardMapper cards;
    private final Clock clock;

    public FavoriteServiceImpl(FavoriteMapper favorites, MaterialMapper materials, CardMapper cards, Clock clock) {
        this.favorites = favorites;
        this.materials = materials;
        this.cards = cards;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void add(long userId, String type, String id) {
        favoriteType(type);
        long targetId = parseId(id);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if ("CARD".equals(type)) {
            if (cards.findOwnedForUpdate(userId, targetId) == null)
                throw new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "卡片不存在");
            favorites.addCard(userId, targetId, now);
            return;
        }
        if (materials.findOwnedForUpdate(targetId, userId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MATERIAL_NOT_FOUND", "资料不存在");
        }
        favorites.addMaterial(userId, targetId, now);
    }

    @Override
    @Transactional
    public void remove(long userId, String type, String id) {
        favoriteType(type);
        if ("CARD".equals(type)) favorites.removeCard(userId, parseId(id));
        else favorites.removeMaterial(userId, parseId(id));
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<FavoriteView> list(long userId, String type, int page, int pageSize) {
        if (type != null) favoriteType(type);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAGE", "分页参数无效");
        }
        long total = favorites.count(userId, type);
        List<FavoriteView> items = favorites.list(userId, type, pageSize, (long) (page - 1) * pageSize)
            .stream().map(row -> new FavoriteView(row.getType(), row.getTargetId().toString(), row.getTitle(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC))).toList();
        return new PageView<>(items, page, pageSize, total);
    }

    private static void favoriteType(String type) {
        if ("MATERIAL".equals(type) || "CARD".equals(type)) return;
        throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_FAVORITE_TYPE", "收藏类型无效");
    }

    private static long parseId(String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "ID格式错误");
        }
        try { return Long.parseLong(id); }
        catch (NumberFormatException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "ID格式错误"); }
    }

}
