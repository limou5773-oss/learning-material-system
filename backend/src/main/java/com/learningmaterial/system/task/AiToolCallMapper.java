package com.learningmaterial.system.task;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiToolCallMapper {
    @Update("""
        UPDATE ai_tool_call c JOIN ai_task t ON t.id=c.task_id
        SET c.status='UNKNOWN',c.error_code='SOURCE_RESTRICTED',c.finished_at=#{now}
        WHERE t.user_id=#{userId} AND t.material_id=#{materialId} AND c.status='RUNNING'
        """)
    int markRestrictedSourceUnknown(@Param("userId")long userId,@Param("materialId")long materialId,@Param("now")LocalDateTime now);
    @Insert("""
        INSERT INTO ai_tool_call (task_id, step_no, attempt_no, execution_epoch, tool_name,
                                  provider_kind, status, started_at)
        VALUES (#{taskId}, #{stepNo}, 1, #{executionEpoch}, #{toolName}, #{providerKind},
                'RUNNING', #{startedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiToolCallRow row);

    @Update("""
        UPDATE ai_tool_call SET status = 'UNKNOWN', error_code = 'LEASE_RECOVERED',
            finished_at = #{now}
        WHERE task_id = #{taskId} AND execution_epoch < #{epoch} AND status = 'RUNNING'
        """)
    int markOlderUnknown(@Param("taskId") long taskId, @Param("epoch") int epoch,
                         @Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_tool_call SET status = 'UNKNOWN', error_code = #{errorCode},
            finished_at = #{now}
        WHERE task_id = #{taskId} AND status = 'RUNNING'
        """)
    int markRunningUnknown(@Param("taskId") long taskId, @Param("errorCode") String errorCode,
                           @Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_tool_call c JOIN ai_task t ON t.id = c.task_id
        SET c.status = 'UNKNOWN', c.error_code = 'SOURCE_DELETED', c.finished_at = #{now}
        WHERE t.user_id = #{userId} AND t.material_id = #{materialId}
          AND t.status = 'CANCELLED' AND t.stage = 'SOURCE_DELETED' AND c.status = 'RUNNING'
        """)
    int markDeletedSourceUnknown(@Param("userId") long userId, @Param("materialId") long materialId,
                                 @Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_tool_call SET status = #{status}, output_summary = #{summary},
            error_code = #{errorCode}, finished_at = #{now}
        WHERE id = #{id} AND task_id = #{taskId} AND execution_epoch = #{epoch}
          AND status = 'RUNNING'
        """)
    int complete(@Param("id") long id, @Param("taskId") long taskId, @Param("epoch") int epoch,
                 @Param("status") String status, @Param("summary") String summary,
                 @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);

    @Select("""
        SELECT c.* FROM ai_tool_call c JOIN ai_task t ON t.id = c.task_id
        WHERE t.id = #{taskId} AND t.user_id = #{userId}
        ORDER BY c.step_no, c.attempt_no LIMIT #{pageSize} OFFSET #{offset}
        """)
    List<AiToolCallRow> listOwned(@Param("userId") long userId, @Param("taskId") long taskId,
                                  @Param("pageSize") int pageSize, @Param("offset") long offset);

    @Select("""
        SELECT COUNT(*) FROM ai_tool_call c JOIN ai_task t ON t.id = c.task_id
        WHERE t.id = #{taskId} AND t.user_id = #{userId}
        """)
    long countOwned(@Param("userId") long userId, @Param("taskId") long taskId);
}
