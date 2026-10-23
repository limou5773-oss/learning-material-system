package com.learningmaterial.system.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Insert("""
        INSERT INTO app_user (username, password_hash, display_name, role, status, created_at, updated_at)
        VALUES (#{username}, #{passwordHash}, #{displayName}, #{role}, #{status}, #{createdAt}, #{updatedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserRow user);

    @Select("SELECT * FROM app_user WHERE username = #{username} LIMIT 1")
    UserRow findByUsername(String username);

    @Select("SELECT * FROM app_user WHERE id = #{id} LIMIT 1")
    UserRow findById(long id);

    @Select("SELECT id FROM app_user WHERE id = #{id} FOR UPDATE")
    Long lockById(long id);
}
