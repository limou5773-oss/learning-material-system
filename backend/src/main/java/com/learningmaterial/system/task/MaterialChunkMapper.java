package com.learningmaterial.system.task;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MaterialChunkMapper {
    @Insert("""
        INSERT INTO material_chunk (material_id, material_version, chunk_no, page_no, content,
                                    extraction_method, quality_note, created_at)
        VALUES (#{materialId}, #{materialVersion}, #{chunkNo}, #{pageNo}, #{content},
                #{extractionMethod}, #{qualityNote}, #{createdAt})
        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrReuse(MaterialChunkRow row);

    @Select("""
        SELECT * FROM material_chunk WHERE material_id = #{materialId}
          AND material_version = #{version} AND chunk_no = #{chunkNo} LIMIT 1
        """)
    MaterialChunkRow findVersionChunk(@Param("materialId") long materialId,
                                      @Param("version") int version, @Param("chunkNo") int chunkNo);

    @Select("""
        SELECT * FROM material_chunk WHERE material_id = #{materialId}
          AND material_version = #{version} AND id = #{id} LIMIT 1
        """)
    MaterialChunkRow findSource(@Param("materialId") long materialId,
                                @Param("version") int version, @Param("id") long id);

    @Select("SELECT * FROM material_chunk WHERE id=#{id} FOR UPDATE")
    MaterialChunkRow findForUpdate(long id);
}
