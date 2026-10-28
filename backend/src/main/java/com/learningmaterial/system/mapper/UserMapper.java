package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.UserRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {
    @Insert("""
        INSERT INTO app_user (username, password_hash, display_name, role, status, created_at, updated_at)
        VALUES (#{username}, #{passwordHash}, #{displayName}, #{role}, #{status}, #{createdAt}, #{updatedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserRow user);

    @Select("SELECT u.*, EXISTS(SELECT 1 FROM wechat_binding w WHERE w.user_id=u.id) AS wechat_bound FROM app_user u WHERE username = #{username} LIMIT 1")
    UserRow findByUsername(String username);

    @Select("SELECT u.*, EXISTS(SELECT 1 FROM wechat_binding w WHERE w.user_id=u.id) AS wechat_bound FROM app_user u WHERE id = #{id} LIMIT 1")
    UserRow findById(long id);

    @Select("SELECT u.*, EXISTS(SELECT 1 FROM wechat_binding w WHERE w.user_id=u.id) AS wechat_bound FROM app_user u WHERE id = #{id} FOR UPDATE")
    UserRow findByIdForUpdate(long id);

    @Select("SELECT u.*, EXISTS(SELECT 1 FROM wechat_binding w WHERE w.user_id=u.id) AS wechat_bound FROM app_user u WHERE username = #{username} LIMIT 1 FOR UPDATE")
    UserRow findByUsernameForUpdate(String username);

    @Update("""
        UPDATE app_user SET display_name=#{displayName},
          password_hash=COALESCE(#{passwordHash},password_hash),updated_at=#{now}
        WHERE id=#{id}
        """)
    int updateProfile(@Param("id") long id, @Param("displayName") String displayName,
                      @Param("passwordHash") String passwordHash, @Param("now") java.time.LocalDateTime now);

    @Select("SELECT id FROM app_user WHERE id = #{id} FOR UPDATE")
    Long lockById(long id);
}
