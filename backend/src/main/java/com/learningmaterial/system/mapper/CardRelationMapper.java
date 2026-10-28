package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.CardRelationRow;
import com.learningmaterial.system.entity.CardRow;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CardRelationMapper {
    String VISIBLE = """
        FROM card_relation r
        JOIN knowledge_card source ON source.id=r.source_card_id AND source.user_id=#{userId} AND source.status != 'DELETED'
        JOIN knowledge_card target ON target.id=r.target_card_id AND target.user_id=#{userId} AND target.status != 'DELETED'
        WHERE r.user_id=#{userId} AND r.origin='USER_CONFIRMED'
        """;
    String CARD_FILTER = "<if test='cardId != null'> AND (r.source_card_id=#{cardId} OR r.target_card_id=#{cardId})</if>";

    @Insert("""
        INSERT INTO card_relation(user_id,source_card_id,target_card_id,relation_type,evidence_text,origin,created_at)
        VALUES(#{userId},#{sourceCardId},#{targetCardId},#{relationType},#{evidenceText},'USER_CONFIRMED',#{createdAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CardRelationRow row);

    @Select("<script>SELECT r.* " + VISIBLE + CARD_FILTER + " ORDER BY r.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<CardRelationRow> listOwned(@Param("userId") long userId, @Param("cardId") Long cardId,
        @Param("limit") int limit, @Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) " + VISIBLE + CARD_FILTER + "</script>")
    long countOwned(@Param("userId") long userId, @Param("cardId") Long cardId);

    @Delete("DELETE FROM card_relation WHERE user_id=#{userId} AND id=#{id}")
    int deleteOwned(@Param("userId") long userId, @Param("id") long id);

    @Delete("DELETE FROM card_relation WHERE user_id=#{userId} AND (source_card_id=#{cardId} OR target_card_id=#{cardId})")
    int deleteForCard(@Param("userId") long userId, @Param("cardId") long cardId);

    @Select("""
        <script>
        SELECT k.*, EXISTS(SELECT 1 FROM favorite f WHERE f.user_id=#{userId} AND f.card_id=k.id) AS favorite
        FROM knowledge_card k WHERE k.user_id=#{userId} AND k.status != 'DELETED'
        <if test='subject != null'> AND k.subject=#{subject}</if>
        <if test='rootCardId != null'>
          AND (k.id=#{rootCardId} OR EXISTS (
            SELECT 1 FROM card_relation r WHERE r.user_id=#{userId} AND r.origin='USER_CONFIRMED'
            AND ((r.source_card_id=#{rootCardId} AND r.target_card_id=k.id)
              OR (r.target_card_id=#{rootCardId} AND r.source_card_id=k.id))))
        </if>
        ORDER BY <if test='rootCardId != null'>(k.id=#{rootCardId}) DESC,</if> k.id ASC LIMIT #{limit}
        </script>
        """)
    List<CardRow> graphNodes(@Param("userId") long userId, @Param("subject") String subject,
        @Param("rootCardId") Long rootCardId, @Param("limit") int limit);

    @Select("<script>SELECT r.* " + VISIBLE + " AND r.source_card_id IN "
        + "<foreach collection='nodeIds' item='id' open='(' close=')' separator=','>#{id}</foreach>"
        + " AND r.target_card_id IN <foreach collection='nodeIds' item='id' open='(' close=')' separator=','>#{id}</foreach>"
        + " ORDER BY r.id ASC LIMIT #{limit}</script>")
    List<CardRelationRow> graphEdges(@Param("userId") long userId, @Param("nodeIds") List<Long> nodeIds, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM knowledge_card WHERE user_id=#{userId} AND id=#{id} AND status != 'DELETED' AND subject=#{subject}")
    int countRootSubject(@Param("userId") long userId, @Param("id") long id, @Param("subject") String subject);
}
