package com.learningmaterial.system.card;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CardMapper {
    @Insert("""
        INSERT INTO knowledge_card(user_id,task_id,candidate_key,title,content,summary,subject,tags_json,
          version,origin,generation_mode,is_edited,status,created_at,updated_at)
        VALUES(#{userId},#{taskId},#{candidateKey},#{title},#{content},#{summary},#{subject},#{tagsJson},
          1,#{origin},#{generationMode},#{isEdited},'ACTIVE',#{createdAt},#{updatedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CardRow card);

    @Update("UPDATE knowledge_card SET parent_card_id=#{parentId} WHERE id=#{id}")
    int setParent(@Param("id") long id, @Param("parentId") long parentId);

    @Insert("""
        INSERT INTO card_source(card_id,chunk_id,quote_text,start_offset,end_offset,created_at)
        VALUES(#{cardId},#{chunkId},#{quote},#{start},#{end},#{now})
        """)
    int insertSource(@Param("cardId") long cardId, @Param("chunkId") long chunkId,
                     @Param("quote") String quote, @Param("start") int start, @Param("end") int end,
                     @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO card_relation(user_id,source_card_id,target_card_id,relation_type,evidence_text,origin,created_at)
        VALUES(#{userId},#{sourceId},#{targetId},#{type},#{evidence},'USER_CONFIRMED',#{now})
        """)
    int insertRelation(@Param("userId") long userId, @Param("sourceId") long sourceId,
                       @Param("targetId") long targetId, @Param("type") String type,
                       @Param("evidence") String evidence, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM knowledge_card WHERE user_id=#{userId} AND id=#{id} AND status != 'DELETED'")
    CardRow findOwned(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT * FROM knowledge_card WHERE user_id=#{userId} AND id=#{id} AND status != 'DELETED' FOR UPDATE")
    CardRow findOwnedForUpdate(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT * FROM knowledge_card WHERE user_id=#{userId} AND status != 'DELETED' ORDER BY updated_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<CardRow> listOwned(@Param("userId") long userId, @Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM knowledge_card WHERE user_id=#{userId} AND status != 'DELETED'")
    long countOwned(long userId);

    @Select("""
        SELECT c.id AS chunk_id,c.material_id,c.material_version,c.page_no,c.content,s.quote_text,
          m.title AS material_title,m.status AS material_status,m.access_state
        FROM card_source s JOIN material_chunk c ON c.id=s.chunk_id
        JOIN material m ON m.id=c.material_id AND m.user_id=#{userId}
        JOIN knowledge_card k ON k.id=s.card_id AND k.user_id=#{userId}
        WHERE s.card_id=#{cardId} ORDER BY s.id
        """)
    List<CardSourceRow> sources(@Param("userId") long userId, @Param("cardId") long cardId);
}
