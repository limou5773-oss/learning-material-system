package com.learningmaterial.system.task;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface PdfPageCacheMapper {
    @Select("SELECT * FROM pdf_page_cache WHERE material_id=#{materialId} AND material_version=#{version} AND page_no BETWEEN #{from} AND #{to} ORDER BY page_no")
    List<PdfPageCacheRow> findRange(@Param("materialId") long materialId, @Param("version") int version,
                                  @Param("from") int from, @Param("to") int to);

    @Insert("""
        INSERT INTO pdf_page_cache(material_id,material_version,page_no,total_pages,extraction_status,page_text,created_at)
        VALUES(#{materialId},#{materialVersion},#{pageNo},#{totalPages},#{extractionStatus},#{pageText},#{createdAt})
        ON DUPLICATE KEY UPDATE id=id
        """)
    int insertOrReuse(PdfPageCacheRow row);
}
