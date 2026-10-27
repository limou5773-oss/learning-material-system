package com.learningmaterial.system.task;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface OcrAttemptMapper {
    @Update("""
        UPDATE ocr_attempt a JOIN ai_task t ON t.id=a.task_id
        SET a.status='UNKNOWN',a.error_code='SOURCE_RESTRICTED',a.finished_at=#{now},
          a.duration_ms=TIMESTAMPDIFF(MICROSECOND,a.started_at,#{now}) DIV 1000
        WHERE t.user_id=#{userId} AND t.material_id=#{materialId} AND a.status='RUNNING'
        """)
    int markRestrictedSourceUnknown(@Param("userId")long userId,@Param("materialId")long materialId,@Param("now")LocalDateTime now);
    @Select("SELECT COUNT(*) FROM ocr_attempt WHERE task_id=#{taskId}")
    int countTask(long taskId);

    @Select("SELECT COUNT(*) FROM ocr_attempt WHERE task_id=#{taskId} AND page_no=#{page}")
    int countPage(@Param("taskId") long taskId, @Param("page") int page);

    @Select(
            "SELECT COUNT(*) FROM ocr_attempt WHERE task_id=#{taskId} AND execution_epoch=#{epoch}"
                + " AND dispatched=1")
    int countDispatched(@Param("taskId") long taskId, @Param("epoch") int epoch);

    @Insert(
            """
INSERT INTO ocr_attempt(task_id,execution_epoch,page_no,attempt_no,provider_key,dispatched,status,started_at,currency)
VALUES(#{taskId},#{executionEpoch},#{pageNo},#{attemptNo},#{providerKey},1,'RUNNING',#{startedAt},#{currency})
""")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OcrAttemptRow row);

    @Update(
            """
UPDATE ocr_attempt SET dispatched=#{dispatched},status=#{status},error_code=#{errorCode},request_id=#{requestId},
  finished_at=#{finishedAt},duration_ms=#{durationMs}
WHERE id=#{id} AND status='RUNNING'
""")
    int complete(OcrAttemptRow row);

    @Update(
            """
            UPDATE ocr_attempt SET status='UNKNOWN',error_code=#{code},finished_at=#{now},
              duration_ms=TIMESTAMPDIFF(MICROSECOND,started_at,#{now}) DIV 1000
            WHERE task_id=#{taskId} AND status='RUNNING'
            """)
    int markRunningUnknown(
            @Param("taskId") long taskId,
            @Param("code") String code,
            @Param("now") LocalDateTime now);

    @Update(
            """
            UPDATE ocr_attempt SET status='UNKNOWN',error_code=#{code},finished_at=#{now},
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
            UPDATE ocr_attempt a JOIN ai_task t ON t.id=a.task_id
            SET a.status='UNKNOWN',a.error_code='SOURCE_DELETED',a.finished_at=#{now},
              a.duration_ms=TIMESTAMPDIFF(MICROSECOND,a.started_at,#{now}) DIV 1000
            WHERE t.user_id=#{userId} AND t.material_id=#{materialId} AND a.status='RUNNING'
            """)
    int markDeletedSourceUnknown(
            @Param("userId") long userId,
            @Param("materialId") long materialId,
            @Param("now") LocalDateTime now);
}
