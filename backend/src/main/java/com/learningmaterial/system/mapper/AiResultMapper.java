package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.AiResultRow;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Select("SELECT * FROM ai_result WHERE task_id = #{taskId} FOR UPDATE")
    AiResultRow findForUpdate(long taskId);

    @Update("UPDATE ai_result SET status='CONFIRMED', confirmation_json=#{receipt}, confirmed_at=#{now}, updated_at=#{now} WHERE task_id=#{taskId} AND status='CANDIDATE'")
    int confirm(@Param("taskId") long taskId, @Param("receipt") String receipt, @Param("now") LocalDateTime now);

    @Update("UPDATE ai_result SET status='INVALIDATED', updated_at=#{now} WHERE task_id=#{taskId} AND status='CANDIDATE'")
    int invalidate(@Param("taskId") long taskId, @Param("now") LocalDateTime now);
}
