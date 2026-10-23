package com.learningmaterial.system.material;

import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class FavoriteService {
    private final FavoriteMapper favorites;
    private final MaterialMapper materials;
    private final Clock clock;

    public FavoriteService(FavoriteMapper favorites, MaterialMapper materials, Clock clock) {
        this.favorites = favorites;
        this.materials = materials;
        this.clock = clock;
    }

    @Transactional
    public void add(long userId, String type, String id) {
        materialType(type);
        long materialId = parseId(id);
        if (materials.findOwnedForUpdate(materialId, userId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MATERIAL_NOT_FOUND", "资料不存在");
        }
        favorites.addMaterial(userId, materialId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    @Transactional
    public void remove(long userId, String type, String id) {
        materialType(type);
        favorites.removeMaterial(userId, parseId(id));
    }

    @Transactional(readOnly = true)
    public PageView<FavoriteView> list(long userId, String type, int page, int pageSize) {
        if (type != null) materialType(type);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAGE", "分页参数无效");
        }
        long total = favorites.countMaterials(userId);
        List<FavoriteView> items = favorites.listMaterials(userId, pageSize, (long) (page - 1) * pageSize)
            .stream().map(row -> new FavoriteView("MATERIAL", row.getMaterialId().toString(), row.getTitle(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC))).toList();
        return new PageView<>(items, page, pageSize, total);
    }

    private static void materialType(String type) {
        if ("MATERIAL".equals(type)) return;
        if ("CARD".equals(type)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
            "UNSUPPORTED_FAVORITE_TYPE", "卡片收藏将在卡片功能完成后开放");
        throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_FAVORITE_TYPE", "收藏类型无效");
    }

    private static long parseId(String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "ID格式错误");
        }
        try { return Long.parseLong(id); }
        catch (NumberFormatException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ID", "ID格式错误"); }
    }

    public record FavoriteView(String type, String targetId, String title, java.time.Instant createdAt) { }
}
