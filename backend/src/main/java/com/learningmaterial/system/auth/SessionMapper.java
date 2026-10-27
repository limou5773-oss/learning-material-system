package com.learningmaterial.system.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SessionMapper {
    @Select("SELECT * FROM auth_session WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    SessionRow findByIdForUpdate(@Param("id") long id, @Param("userId") long userId);
    @Insert("""
        INSERT INTO auth_session (user_id, token_hash, client_type, expires_at, revoked_at, created_at, updated_at)
        VALUES (#{userId}, #{tokenHash}, #{clientType}, #{expiresAt}, #{revokedAt}, #{createdAt}, #{updatedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SessionRow session);

    @Select("""
        SELECT * FROM auth_session
        WHERE token_hash = #{tokenHash} AND revoked_at IS NULL AND expires_at > #{now}
        LIMIT 1
        """)
    SessionRow findActive(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Select("""
        SELECT * FROM auth_session WHERE id=#{id} AND user_id=#{userId}
        AND revoked_at IS NULL AND expires_at > #{now} FOR UPDATE
        """)
    SessionRow findActiveById(@Param("id") long id, @Param("userId") long userId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE auth_session SET revoked_at=#{now},updated_at=#{now}
        WHERE user_id=#{userId} AND revoked_at IS NULL
        """)
    int revokeAll(@Param("userId") long userId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE auth_session SET revoked_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND revoked_at IS NULL
        """)
    int revoke(@Param("id") long id, @Param("now") LocalDateTime now);
}
