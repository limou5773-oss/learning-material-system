package com.learningmaterial.system.material;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FavoriteMapper {
    @Insert("""
        INSERT IGNORE INTO favorite (user_id, material_id, created_at)
        VALUES (#{userId}, #{materialId}, #{now})
        """)
    int addMaterial(@Param("userId") long userId, @Param("materialId") long materialId,
                    @Param("now") LocalDateTime now);

    @Delete("DELETE FROM favorite WHERE user_id = #{userId} AND material_id = #{materialId}")
    int removeMaterial(@Param("userId") long userId, @Param("materialId") long materialId);

    @Select("SELECT EXISTS(SELECT 1 FROM favorite WHERE user_id = #{userId} AND material_id = #{materialId})")
    boolean hasMaterial(@Param("userId") long userId, @Param("materialId") long materialId);

    @Select("""
        SELECT f.id, f.material_id, m.title, f.created_at
        FROM favorite f JOIN material m ON m.id = f.material_id AND m.user_id = f.user_id
        WHERE f.user_id = #{userId} AND m.status != 'DELETED'
        ORDER BY f.created_at DESC, f.id DESC LIMIT #{pageSize} OFFSET #{offset}
        """)
    List<FavoriteRow> listMaterials(@Param("userId") long userId, @Param("pageSize") int pageSize,
                                    @Param("offset") long offset);

    @Select("""
        SELECT COUNT(*) FROM favorite f JOIN material m ON m.id = f.material_id AND m.user_id = f.user_id
        WHERE f.user_id = #{userId} AND m.status != 'DELETED'
        """)
    long countMaterials(@Param("userId") long userId);
}
