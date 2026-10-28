package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.TagRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TagMapper {
    @Insert("""
        INSERT INTO tag (user_id, name, created_at, updated_at)
        VALUES (#{userId}, #{name}, #{now}, #{now})
        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
        """)
    int insertOrGet(@Param("userId") long userId, @Param("name") String name,
                    @Param("now") LocalDateTime now);

    @Select("SELECT id, user_id, name FROM tag WHERE user_id = #{userId} AND name = #{name} LIMIT 1")
    TagRow findOwnedByName(@Param("userId") long userId, @Param("name") String name);

    @Insert("""
        INSERT INTO material_tag (material_id, tag_id, created_at, updated_at)
        VALUES (#{materialId}, #{tagId}, #{now}, #{now})
        """)
    int attach(@Param("materialId") long materialId, @Param("tagId") long tagId,
               @Param("now") LocalDateTime now);

    @Select("""
        SELECT t.id, t.user_id, t.name FROM tag t
        JOIN material_tag mt ON mt.tag_id = t.id
        WHERE mt.material_id = #{materialId} ORDER BY t.name, t.id
        """)
    List<TagRow> forMaterial(long materialId);

    @Delete("DELETE FROM material_tag WHERE material_id = #{materialId}")
    int detachAll(long materialId);

    @Select("""
        <script>
        SELECT id, user_id, name FROM tag WHERE user_id = #{userId}
        <if test="keyword != null">AND LOCATE(#{keyword}, name) &gt; 0</if>
        ORDER BY name, id LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<TagRow> listOwned(@Param("userId") long userId, @Param("keyword") String keyword,
                           @Param("pageSize") int pageSize, @Param("offset") long offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM tag WHERE user_id = #{userId}
        <if test="keyword != null">AND LOCATE(#{keyword}, name) &gt; 0</if>
        </script>
        """)
    long countOwned(@Param("userId") long userId, @Param("keyword") String keyword);
}
