package com.learningmaterial.system.task;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiResultMapper {
    @Insert("""
        INSERT INTO ai_result (task_id, version, candidate_json, validation_json, status,
                               created_at, updated_at)
        VALUES (#{taskId}, #{version}, #{candidateJson}, #{validationJson}, #{status},
                #{createdAt}, #{updatedAt})
        """)
    int insert(AiResultRow row);

    @Select("""
        SELECT r.* FROM ai_result r JOIN ai_task t ON t.id = r.task_id
        WHERE t.user_id = #{userId} AND r.task_id = #{taskId} LIMIT 1
        """)
    AiResultRow findOwned(@Param("userId") long userId, @Param("taskId") long taskId);
}
