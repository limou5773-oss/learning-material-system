package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.UserRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AdminUserMapper {
    @Select("SELECT id FROM admin_control_lock WHERE id=1 FOR UPDATE")
    Integer lockControl();

    @Select("SELECT COUNT(*) FROM app_user WHERE role='ADMIN'")
    long countAdmins();

    @Select("SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND status='ENABLED'")
    long countEnabledAdmins();

    @Select("""
        <script>
        SELECT id,username,display_name,role,status,created_at,
          EXISTS(SELECT 1 FROM wechat_binding w WHERE w.user_id=app_user.id) AS wechat_bound FROM app_user
        <where>
          <if test="keyword != null">(username LIKE CONCAT('%',#{keyword},'%') ESCAPE '!' OR display_name LIKE CONCAT('%',#{keyword},'%') ESCAPE '!')</if>
          <if test="status != null">AND status=#{status}</if>
        </where>
        ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<UserRow> list(@Param("keyword") String keyword, @Param("status") String status,
                       @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
        <script>
        SELECT COUNT(*) FROM app_user
        <where>
          <if test="keyword != null">(username LIKE CONCAT('%',#{keyword},'%') ESCAPE '!' OR display_name LIKE CONCAT('%',#{keyword},'%') ESCAPE '!')</if>
          <if test="status != null">AND status=#{status}</if>
        </where>
        </script>
        """)
    long count(@Param("keyword") String keyword, @Param("status") String status);

    @Update("UPDATE app_user SET status=#{status},updated_at=#{now} WHERE id=#{id}")
    int updateStatus(@Param("id") long id, @Param("status") String status, @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO review_record(operator_id,target_user_id,action,reason,before_status,after_status,created_at)
        VALUES(#{actor},#{target},'USER_STATUS',#{reason},#{before},#{after},#{now})
        """)
    int audit(@Param("actor") long actor, @Param("target") long target, @Param("reason") String reason,
              @Param("before") String before, @Param("after") String after, @Param("now") LocalDateTime now);
}
