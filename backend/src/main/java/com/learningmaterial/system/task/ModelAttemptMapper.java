package com.learningmaterial.system.task;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface ModelAttemptMapper {
    @Update("""
        UPDATE model_attempt a JOIN ai_task t ON t.id=a.task_id
        SET a.status='UNKNOWN',a.error_code='SOURCE_RESTRICTED',a.finished_at=#{now},
          a.duration_ms=TIMESTAMPDIFF(MICROSECOND,a.started_at,#{now}) DIV 1000
        WHERE t.user_id=#{userId} AND t.material_id=#{materialId} AND a.status='RUNNING'
        """)
    int markRestrictedSourceUnknown(@Param("userId")long userId,@Param("materialId")long materialId,@Param("now")LocalDateTime now);
    @Select("SELECT COUNT(*) FROM model_attempt WHERE task_id=#{taskId}")
    int countTask(long taskId);

    @Select("SELECT COUNT(*) FROM model_attempt WHERE task_id=#{taskId} AND purpose=#{purpose}")
    int countPurpose(@Param("taskId") long taskId, @Param("purpose") String purpose);

    @Select(
            "SELECT COUNT(*) FROM model_attempt WHERE task_id=#{taskId} AND"
                    + " execution_epoch=#{epoch} AND dispatched=1")
    int countDispatched(@Param("taskId") long taskId, @Param("epoch") int epoch);

    @Insert(
            """
INSERT INTO model_attempt(task_id,execution_epoch,attempt_no,purpose,provider_key,requested_model,prompt_version,dispatched,status,started_at)
VALUES(#{taskId},#{executionEpoch},#{attemptNo},#{purpose},#{providerKey},#{requestedModel},#{promptVersion},1,'RUNNING',#{startedAt})
""")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ModelAttemptRow row);

    @Update(
            """
UPDATE model_attempt SET dispatched=#{dispatched},status=#{status},error_code=#{errorCode},request_id=#{requestId},
  finished_at=#{finishedAt},duration_ms=#{durationMs},response_model=#{responseModel},input_tokens=#{inputTokens},output_tokens=#{outputTokens}
WHERE id=#{id} AND status='RUNNING'
""")
    int complete(ModelAttemptRow row);

    /** A late known response enriches its own attempt without changing a terminal UNKNOWN state. */
    @Update(
            """
            UPDATE model_attempt SET response_model=#{responseModel},request_id=#{requestId},
              input_tokens=#{inputTokens},output_tokens=#{outputTokens}
            WHERE id=#{id} AND execution_epoch=#{executionEpoch} AND response_model IS NULL
            """)
    int recordResponse(ModelAttemptRow row);

    @Update(
            """
            UPDATE model_attempt SET status='UNKNOWN',error_code=#{code},finished_at=#{now},
              duration_ms=TIMESTAMPDIFF(MICROSECOND,started_at,#{now}) DIV 1000
            WHERE task_id=#{taskId} AND status='RUNNING'
            """)
    int markRunningUnknown(
            @Param("taskId") long taskId,
            @Param("code") String code,
            @Param("now") LocalDateTime now);

    @Update(
            """
            UPDATE model_attempt SET status='UNKNOWN',error_code=#{code},finished_at=#{now},
              duration_ms=TIMESTAMPDIFF(MICROSECOND,started_at,#{now}) DIV 1000
            WHERE task_id=#{taskId} AND execution_epoch=#{epoch} AND status='RUNNING'
            """)
    int markEpochUnknown(
            @Param("taskId") long taskId,
            @Param("epoch") int epoch,
            @Param("code") String code,
            @Param("now") LocalDateTime now);

    @Update(
            """
            UPDATE model_attempt a JOIN ai_task t ON t.id=a.task_id
            SET a.status='UNKNOWN',a.error_code='SOURCE_DELETED',a.finished_at=#{now},
              a.duration_ms=TIMESTAMPDIFF(MICROSECOND,a.started_at,#{now}) DIV 1000
            WHERE t.user_id=#{userId} AND t.material_id=#{materialId} AND a.status='RUNNING'
            """)
    int markDeletedSourceUnknown(
            @Param("userId") long userId,
            @Param("materialId") long materialId,
            @Param("now") LocalDateTime now);
}
