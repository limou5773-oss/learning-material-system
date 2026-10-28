package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.CardRow;
import com.learningmaterial.system.entity.MaterialRow;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PersonalMaterialSearchMapper {
    String ACCESSIBLE =
            """
k.user_id=#{userId} AND k.status!='DELETED'
AND NOT EXISTS (
  SELECT 1 FROM card_source s JOIN material_chunk c ON c.id=s.chunk_id
  JOIN material m ON m.id=c.material_id
  WHERE s.card_id=k.id AND (m.user_id!=#{userId} OR m.status='DELETED' OR m.access_state!='NORMAL')
)
""";

    @Select(
            "SELECT k.* FROM knowledge_card k WHERE "
                    + ACCESSIBLE
                    + """
AND (#{subject} IS NULL OR k.subject=#{subject})
AND (LOCATE(#{keywords},k.title)>0 OR LOCATE(#{keywords},k.content)>0 OR LOCATE(#{keywords},k.tags_json)>0)
ORDER BY k.updated_at DESC,k.id DESC LIMIT #{limit}
""")
    List<CardRow> search(
            @Param("userId") long userId,
            @Param("keywords") String keywords,
            @Param("subject") String subject,
            @Param("limit") int limit);

    @Select("SELECT k.* FROM knowledge_card k WHERE " + ACCESSIBLE + " AND k.id=#{id}")
    CardRow findAvailable(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT k.* FROM knowledge_card k WHERE " + ACCESSIBLE + " AND k.id=#{id} FOR UPDATE")
    CardRow lockAvailable(@Param("userId") long userId, @Param("id") long id);

    @Select(
            """
<script>
SELECT m.id,m.user_id,m.status,m.access_state FROM material m
WHERE m.user_id=#{userId} AND EXISTS (
  SELECT 1 FROM card_source s JOIN material_chunk c ON c.id=s.chunk_id
  WHERE c.material_id=m.id AND s.card_id IN
  <foreach collection="cardIds" item="cardId" open="(" separator="," close=")">#{cardId}</foreach>
) ORDER BY m.id FOR UPDATE
</script>
""")
    List<MaterialRow> lockSourceMaterials(
            @Param("userId") long userId, @Param("cardIds") List<Long> cardIds);

    @Select(
            """
<script>
SELECT m.id,m.user_id,m.status,m.access_state FROM material m
WHERE m.user_id=#{userId} AND (m.id=#{primaryMaterialId}
<if test="!cardIds.isEmpty()">
 OR EXISTS (SELECT 1 FROM card_source s JOIN material_chunk c ON c.id=s.chunk_id
   WHERE c.material_id=m.id AND s.card_id IN
   <foreach collection="cardIds" item="cardId" open="(" separator="," close=")">#{cardId}</foreach>)
</if>
) ORDER BY m.id FOR UPDATE
</script>
""")
    List<MaterialRow> lockMaterials(
            @Param("userId") long userId,
            @Param("primaryMaterialId") long primaryMaterialId,
            @Param("cardIds") List<Long> cardIds);
}
