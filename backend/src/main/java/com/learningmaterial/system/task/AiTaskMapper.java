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
public interface AiTaskMapper {
    @Update("""
        UPDATE ai_task SET status='CANCELLED',stage='SOURCE_RESTRICTED',cancel_flag=1,
          error_code='SOURCE_RESTRICTED',lease_until=NULL,finished_at=#{now},updated_at=#{now}
        WHERE user_id=#{userId} AND material_id=#{materialId} AND status IN ('QUEUED','RUNNING','WAIT_CONFIRM')
        """)
    int cancelForRestrictedMaterial(@Param("userId")long userId,@Param("materialId")long materialId,@Param("now")LocalDateTime now);
    @Update("UPDATE ai_task SET status='SUCCEEDED', stage='CONFIRMED', finished_at=#{now}, updated_at=#{now}, lease_until=NULL WHERE id=#{id} AND status='WAIT_CONFIRM' AND cancel_flag=0")
    int confirm(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE ai_task SET status='FAILED', stage='FAILED', error_code='SOURCE_VERSION_CHANGED', finished_at=#{now}, updated_at=#{now}, lease_until=NULL WHERE id=#{id} AND status='WAIT_CONFIRM'")
    int failStaleConfirmation(@Param("id") long id, @Param("now") LocalDateTime now);
    @Insert("""
        INSERT INTO ai_task (user_id, material_id, material_version, request_key, request_hash, parent_task_id,
                             goal_type, instruction, status, stage, step_count, cancel_flag,
                             execution_epoch, config_json, created_at, updated_at)
        VALUES (#{userId}, #{materialId}, #{materialVersion}, #{requestKey}, #{requestHash}, #{parentTaskId},
                #{goalType}, #{instruction}, #{status}, #{stage}, #{stepCount}, #{cancelFlag},
                #{executionEpoch}, #{configJson}, #{createdAt}, #{updatedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiTaskRow row);

    @Select("SELECT * FROM ai_task WHERE user_id = #{userId} AND request_key = #{requestKey} LIMIT 1")
    AiTaskRow findByRequest(@Param("userId") long userId, @Param("requestKey") String requestKey);

    @Select("SELECT * FROM ai_task WHERE user_id = #{userId} AND id = #{id} LIMIT 1")
    AiTaskRow findOwned(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT * FROM ai_task WHERE user_id = #{userId} AND id = #{id} FOR UPDATE")
    AiTaskRow findOwnedForUpdate(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT * FROM ai_task WHERE id = #{id} FOR UPDATE")
    AiTaskRow findByIdForUpdate(@Param("id") long id);

    @Select("SELECT * FROM ai_task WHERE id = #{id} LIMIT 1")
    AiTaskRow findById(@Param("id") long id);

    @Select("""
        SELECT * FROM ai_task
        WHERE (status = 'QUEUED' OR (status = 'RUNNING' AND lease_until < #{now}))
          AND cancel_flag = 0
        ORDER BY CASE WHEN status = 'RUNNING' THEN 0 ELSE 1 END, id
        LIMIT 1 FOR UPDATE SKIP LOCKED
        """)
    AiTaskRow findClaimable(@Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_task SET status = 'RUNNING', stage = 'STARTING',
            execution_epoch = execution_epoch + 1, lease_until = #{leaseUntil},
            started_at = COALESCE(started_at, #{now}), updated_at = #{now}
        WHERE id = #{id} AND execution_epoch = #{previousEpoch} AND cancel_flag = 0
          AND (status = 'QUEUED' OR (status = 'RUNNING' AND lease_until < #{now}))
        """)
    int claim(@Param("id") long id, @Param("previousEpoch") int previousEpoch,
              @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
        UPDATE ai_task SET lease_until = #{leaseUntil}, updated_at = #{now}
        WHERE id = #{id} AND execution_epoch = #{epoch} AND status = 'RUNNING'
          AND cancel_flag = 0 AND lease_until > #{now}
        """)
    int heartbeat(@Param("id") long id, @Param("epoch") int epoch,
                  @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
        SELECT COUNT(*) FROM ai_task WHERE id = #{id} AND execution_epoch = #{epoch}
          AND status = 'RUNNING' AND cancel_flag = 0 AND lease_until > #{now}
          AND started_at > #{deadlineStart}
        """)
    long countCurrent(@Param("id") long id, @Param("epoch") int epoch,
                      @Param("now") LocalDateTime now, @Param("deadlineStart") LocalDateTime deadlineStart);

    @Update("""
        UPDATE ai_task SET step_count = step_count + 1, stage = #{stage}, updated_at = #{now}
        WHERE id = #{id} AND execution_epoch = #{epoch} AND status = 'RUNNING'
          AND cancel_flag = 0 AND lease_until > #{now} AND step_count < 8
        """)
    int advanceStep(@Param("id") long id, @Param("epoch") int epoch,
                    @Param("stage") String stage, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_task SET context_json = #{contextJson}, stage = #{stage}, updated_at = #{now}
        WHERE id = #{id} AND execution_epoch = #{epoch} AND status = 'RUNNING'
          AND cancel_flag = 0 AND lease_until > #{now} AND started_at > #{deadlineStart}
        """)
    int saveProgress(@Param("id") long id, @Param("epoch") int epoch,
                     @Param("stage") String stage, @Param("contextJson") String contextJson,
                     @Param("now") LocalDateTime now, @Param("deadlineStart") LocalDateTime deadlineStart);

    @Update("""
        UPDATE ai_task SET status = 'FAILED', stage = 'FAILED', error_code = #{errorCode},
            lease_until = NULL, finished_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND execution_epoch = #{epoch} AND status = 'RUNNING'
          AND cancel_flag = 0 AND lease_until > #{now}
        """)
    int failOwnedLease(@Param("id") long id, @Param("epoch") int epoch,
                       @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_task SET status = 'WAIT_CONFIRM', stage = 'CANDIDATE_READY',
            lease_until = NULL, updated_at = #{now}
        WHERE id = #{id} AND execution_epoch = #{epoch} AND status = 'RUNNING'
          AND cancel_flag = 0 AND lease_until > #{now}
          AND started_at > #{deadlineStart}
        """)
    int markCandidateReady(@Param("id") long id, @Param("epoch") int epoch,
                           @Param("now") LocalDateTime now,
                           @Param("deadlineStart") LocalDateTime deadlineStart);

    @Update("""
        UPDATE ai_task SET status = 'FAILED', stage = 'FAILED', error_code = 'TASK_TIMEOUT',
            lease_until = NULL, finished_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND execution_epoch = #{epoch} AND status = 'RUNNING'
          AND cancel_flag = 0 AND lease_until < #{now}
        """)
    int failTimedOut(@Param("id") long id, @Param("epoch") int epoch, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE ai_task SET status = 'CANCELLED', stage = 'CANCELLED', cancel_flag = 1,
            lease_until = NULL, finished_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND user_id = #{userId}
          AND status IN ('QUEUED', 'RUNNING', 'WAIT_CONFIRM')
        """)
    int cancelOwned(@Param("id") long id, @Param("userId") long userId,
                    @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM ai_task WHERE user_id = #{userId} AND status IN ('QUEUED', 'RUNNING')")
    long countActive(@Param("userId") long userId);

    @Select("""
        <script>
        SELECT * FROM ai_task WHERE user_id = #{userId}
        <if test="status != null">AND status = #{status}</if>
        ORDER BY created_at DESC, id DESC LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<AiTaskRow> listOwned(@Param("userId") long userId, @Param("status") String status,
                              @Param("pageSize") int pageSize, @Param("offset") long offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM ai_task WHERE user_id = #{userId}
        <if test="status != null">AND status = #{status}</if>
        </script>
        """)
    long countOwned(@Param("userId") long userId, @Param("status") String status);

    @Update("""
        UPDATE ai_task SET status = 'CANCELLED', stage = 'SOURCE_DELETED', cancel_flag = 1,
            error_code = 'SOURCE_DELETED', lease_until = NULL, finished_at = #{now}, updated_at = #{now}
        WHERE user_id = #{userId} AND material_id = #{materialId}
          AND status IN ('QUEUED', 'RUNNING', 'WAIT_CONFIRM')
        """)
    int cancelForDeletedMaterial(@Param("userId") long userId, @Param("materialId") long materialId,
                                 @Param("now") LocalDateTime now);
}
