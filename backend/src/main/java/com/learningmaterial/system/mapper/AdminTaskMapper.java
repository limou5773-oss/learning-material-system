package com.learningmaterial.system.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminTaskMapper {
    record TaskRow(long id, long userId, long materialId, String goalType, String status,
                   String stage, int stepCount, String errorCode, LocalDateTime createdAt,
                   LocalDateTime startedAt, LocalDateTime finishedAt) { }

    record StepRow(int stepNo, int attemptNo, String toolName, String providerKind,
                   String status, String errorCode, LocalDateTime startedAt, LocalDateTime finishedAt) { }

    @Select("""
        <script>
        SELECT COUNT(*) FROM ai_task
        <where>
          <if test="status != null">status=#{status}</if>
          <if test="from != null">AND created_at &gt;= #{from}</if>
          <if test="to != null">AND created_at &lt; #{to}</if>
        </where>
        </script>
        """)
    long count(@Param("status") String status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
        <script>
        SELECT id,user_id,material_id,goal_type,status,stage,step_count,error_code,created_at,started_at,finished_at
        FROM ai_task
        <where>
          <if test="status != null">status=#{status}</if>
          <if test="from != null">AND created_at &gt;= #{from}</if>
          <if test="to != null">AND created_at &lt; #{to}</if>
        </where>
        ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<TaskRow> list(@Param("status") String status, @Param("from") LocalDateTime from,
                       @Param("to") LocalDateTime to, @Param("offset") long offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ai_task WHERE id=#{id}")
    boolean exists(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM ai_tool_call WHERE task_id=#{taskId}")
    long countSteps(@Param("taskId") long taskId);

    @Select("""
        SELECT step_no,attempt_no,tool_name,provider_kind,status,error_code,started_at,finished_at
        FROM ai_tool_call WHERE task_id=#{taskId}
        ORDER BY step_no ASC,attempt_no ASC,id ASC LIMIT #{limit} OFFSET #{offset}
        """)
    List<StepRow> listSteps(@Param("taskId") long taskId, @Param("offset") long offset, @Param("limit") int limit);
}
