package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.MaterialRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MaterialMapper {
    @Insert("""
        INSERT INTO material (user_id, title, subject, material_type, note_text, version,
                              status, access_state, created_at, updated_at)
        VALUES (#{userId}, #{title}, #{subject}, #{materialType}, #{noteText}, #{version},
                #{status}, #{accessState}, #{createdAt}, #{updatedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MaterialRow row);

    @Insert("""
        INSERT INTO material (user_id, title, subject, material_type, storage_key, mime_type,
                              file_size, file_hash, page_count, version, status, access_state, created_at, updated_at)
        VALUES (#{userId}, #{title}, #{subject}, #{materialType}, #{storageKey}, #{mimeType},
                #{fileSize}, #{fileHash}, #{pageCount}, #{version}, #{status}, #{accessState}, #{createdAt}, #{updatedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFile(MaterialRow row);

    @Select("""
        SELECT * FROM material WHERE user_id = #{userId} AND file_hash = #{hash}
          AND status != 'DELETED' LIMIT 1
        """)
    MaterialRow findOwnedByHash(@Param("userId") long userId, @Param("hash") String hash);

    @Select("""
        SELECT * FROM material WHERE id = #{id} AND user_id = #{userId} AND status != 'DELETED' LIMIT 1
        """)
    MaterialRow findOwned(@Param("id") long id, @Param("userId") long userId);

    @Select("SELECT * FROM material WHERE id = #{id} AND user_id = #{userId} AND status != 'DELETED' LIMIT 1 FOR UPDATE")
    MaterialRow findOwnedForUpdate(@Param("id") long id, @Param("userId") long userId);

    @Update("""
        UPDATE material SET title = #{title}, subject = #{subject}, note_text = #{noteText},
            summary = #{summary}, status = #{status}, version = version + 1, updated_at = #{now}
        WHERE id = #{id} AND user_id = #{userId} AND version = #{expectedVersion} AND status != 'DELETED'
        """)
    int updateConditional(@Param("id") long id, @Param("userId") long userId,
                          @Param("expectedVersion") int expectedVersion, @Param("title") String title,
                          @Param("subject") String subject, @Param("noteText") String noteText,
                          @Param("summary") String summary, @Param("status") String status,
                          @Param("now") LocalDateTime now);

    @Update("""
        UPDATE material SET status = 'DELETED', version = version + 1, updated_at = #{now}
        WHERE id = #{id} AND user_id = #{userId} AND version = #{expectedVersion} AND status != 'DELETED'
        """)
    int softDeleteConditional(@Param("id") long id, @Param("userId") long userId,
                              @Param("expectedVersion") int expectedVersion, @Param("now") LocalDateTime now);

    @Select("""
        <script>
        SELECT m.* FROM material m
        WHERE m.user_id = #{userId} AND m.status != 'DELETED'
        <if test="status != 'ALL'">AND m.status = #{status}</if>
        <if test="keyword != null">AND (LOCATE(#{keyword}, m.title) &gt; 0 OR LOCATE(#{keyword}, m.note_text) &gt; 0)</if>
        <if test="subject != null">AND m.subject = #{subject}</if>
        <if test="tagId != null">AND EXISTS (SELECT 1 FROM material_tag mt WHERE mt.material_id = m.id AND mt.tag_id = #{tagId})</if>
        <if test="favoriteOnly">AND EXISTS (SELECT 1 FROM favorite f WHERE f.user_id = m.user_id AND f.material_id = m.id)</if>
        ORDER BY m.updated_at DESC, m.id DESC LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<MaterialRow> listOwned(@Param("userId") long userId, @Param("status") String status,
                                @Param("keyword") String keyword, @Param("subject") String subject,
                                @Param("tagId") Long tagId, @Param("favoriteOnly") boolean favoriteOnly,
                                @Param("pageSize") int pageSize,
                                @Param("offset") long offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM material m
        WHERE m.user_id = #{userId} AND m.status != 'DELETED'
        <if test="status != 'ALL'">AND m.status = #{status}</if>
        <if test="keyword != null">AND (LOCATE(#{keyword}, m.title) &gt; 0 OR LOCATE(#{keyword}, m.note_text) &gt; 0)</if>
        <if test="subject != null">AND m.subject = #{subject}</if>
        <if test="tagId != null">AND EXISTS (SELECT 1 FROM material_tag mt WHERE mt.material_id = m.id AND mt.tag_id = #{tagId})</if>
        <if test="favoriteOnly">AND EXISTS (SELECT 1 FROM favorite f WHERE f.user_id = m.user_id AND f.material_id = m.id)</if>
        </script>
        """)
    long countOwned(@Param("userId") long userId, @Param("status") String status,
                    @Param("keyword") String keyword, @Param("subject") String subject,
                    @Param("tagId") Long tagId, @Param("favoriteOnly") boolean favoriteOnly);
}
