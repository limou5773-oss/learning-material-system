package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.FavoriteRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FavoriteMapper {
    @Insert("""
        INSERT IGNORE INTO favorite (user_id, material_id, created_at)
        VALUES (#{userId}, #{materialId}, #{now})
        """)
    int addMaterial(@Param("userId") long userId, @Param("materialId") long materialId,
                    @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO favorite(user_id,card_id,created_at) VALUES(#{userId},#{cardId},#{now})
        ON DUPLICATE KEY UPDATE id=id
        """)
    int addCard(@Param("userId") long userId, @Param("cardId") long cardId, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM favorite WHERE user_id=#{userId} AND card_id=#{cardId}")
    int removeCard(@Param("userId") long userId, @Param("cardId") long cardId);

    @Delete("DELETE FROM favorite WHERE user_id = #{userId} AND material_id = #{materialId}")
    int removeMaterial(@Param("userId") long userId, @Param("materialId") long materialId);

    @Select("SELECT EXISTS(SELECT 1 FROM favorite WHERE user_id = #{userId} AND material_id = #{materialId})")
    boolean hasMaterial(@Param("userId") long userId, @Param("materialId") long materialId);

    String VISIBLE_TARGETS = """
        FROM favorite f
        LEFT JOIN material m ON m.id=f.material_id AND m.user_id=f.user_id AND m.status != 'DELETED'
        LEFT JOIN knowledge_card k ON k.id=f.card_id AND k.user_id=f.user_id AND k.status != 'DELETED'
        WHERE f.user_id=#{userId} AND (m.id IS NOT NULL OR k.id IS NOT NULL)
        <if test="type == 'MATERIAL'">AND m.id IS NOT NULL</if>
        <if test="type == 'CARD'">AND k.id IS NOT NULL</if>
        """;

    @Select("<script>SELECT f.id, COALESCE(m.id,k.id) AS target_id, COALESCE(m.title,k.title) AS title,"
        + " CASE WHEN m.id IS NOT NULL THEN 'MATERIAL' ELSE 'CARD' END AS type, f.created_at "
        + VISIBLE_TARGETS + " ORDER BY f.created_at DESC,f.id DESC LIMIT #{pageSize} OFFSET #{offset}</script>")
    List<FavoriteRow> list(@Param("userId") long userId, @Param("type") String type,
        @Param("pageSize") int pageSize, @Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) " + VISIBLE_TARGETS + "</script>")
    long count(@Param("userId") long userId, @Param("type") String type);
}
