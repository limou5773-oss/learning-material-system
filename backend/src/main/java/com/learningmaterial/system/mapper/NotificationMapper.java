package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.NotificationRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NotificationMapper {
    @Insert("""
        INSERT INTO notification(user_id,review_id,type,dedupe_key,content,target_json,channel_status,created_at)
        VALUES(#{userId},#{reviewId},'REVIEW_DECIDED',CONCAT('review:',#{reviewId},':DECIDED'),'资料审核已有处理结果，请查看审核历史。',
          JSON_OBJECT('type','MATERIAL','materialId',CAST(#{materialId} AS CHAR),'reviewId',CAST(#{reviewId} AS CHAR)),'NOT_REQUESTED',#{now})
        """)
    int insertReviewEvent(@Param("userId")long userId,@Param("reviewId")long reviewId,@Param("materialId")long materialId,@Param("now")LocalDateTime now);
    @Insert("""
        INSERT INTO notification (user_id, task_id, type, dedupe_key, content, target_json, channel_status, created_at)
        VALUES (#{userId}, #{taskId}, #{type}, #{dedupeKey}, #{content},
          JSON_OBJECT('type', 'AI_TASK', 'taskId', CAST(#{taskId} AS CHAR)), 'NOT_REQUESTED', #{now})
        ON DUPLICATE KEY UPDATE id=id
        """)
    int insertEvent(@Param("userId") long userId, @Param("taskId") long taskId,
                    @Param("type") String type, @Param("dedupeKey") String dedupeKey,
                    @Param("content") String content, @Param("now") LocalDateTime now);

    @Select("""
        <script>
        SELECT * FROM notification WHERE user_id=#{userId}
        <if test="unreadOnly">AND read_at IS NULL</if>
        ORDER BY created_at DESC, id DESC LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<NotificationRow> listOwned(@Param("userId") long userId, @Param("unreadOnly") boolean unreadOnly,
                                    @Param("pageSize") int pageSize, @Param("offset") long offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM notification WHERE user_id=#{userId}
        <if test="unreadOnly">AND read_at IS NULL</if>
        </script>
        """)
    long countOwned(@Param("userId") long userId, @Param("unreadOnly") boolean unreadOnly);

    @Update("""
        UPDATE notification SET read_at=CASE WHEN #{isRead} THEN COALESCE(read_at, #{now}) ELSE NULL END
        WHERE id=#{id} AND user_id=#{userId}
        """)
    int setReadState(@Param("userId") long userId, @Param("id") long id,
                     @Param("isRead") boolean isRead, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM notification WHERE id=#{id} AND user_id=#{userId}")
    NotificationRow findOwned(@Param("userId") long userId, @Param("id") long id);
}
