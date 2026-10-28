package com.learningmaterial.system.mapper;

import com.learningmaterial.system.entity.WechatBindingRow;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WechatBindingMapper {
    @Select("SELECT * FROM wechat_binding WHERE app_id=#{appId} AND open_id=#{openId}")
    WechatBindingRow findByIdentity(@Param("appId") String appId, @Param("openId") String openId);

    @Select("SELECT * FROM wechat_binding WHERE user_id=#{userId} FOR UPDATE")
    WechatBindingRow findByUserForUpdate(long userId);

    @Insert("""
        INSERT INTO wechat_binding(user_id,app_id,open_id,created_at,updated_at)
        VALUES(#{userId},#{appId},#{openId},#{now},#{now})
        """)
    int insert(@Param("userId") long userId, @Param("appId") String appId,
               @Param("openId") String openId, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM wechat_binding WHERE user_id=#{userId}")
    int deleteByUser(long userId);
}
