package com.learningmaterial.system.task;

import org.apache.ibatis.annotations.*;

@Mapper
public interface OcrPageCacheMapper {
    @Select(
            "SELECT * FROM ocr_page_cache WHERE material_id=#{materialId} AND"
                + " material_version=#{version} AND page_no=#{page}")
    OcrPageCacheRow find(
            @Param("materialId") long materialId,
            @Param("version") int version,
            @Param("page") int page);

    @Insert(
            """
INSERT INTO ocr_page_cache(material_id,material_version,page_no,provider_key,extraction_status,page_text,confidence,request_id,created_at)
VALUES(#{materialId},#{materialVersion},#{pageNo},#{providerKey},#{extractionStatus},#{pageText},#{confidence},#{requestId},#{createdAt})
ON DUPLICATE KEY UPDATE id=id
""")
    int insertOrReuse(OcrPageCacheRow row);
}
