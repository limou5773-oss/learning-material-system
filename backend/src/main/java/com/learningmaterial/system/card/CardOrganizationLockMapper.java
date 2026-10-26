package com.learningmaterial.system.card;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CardOrganizationLockMapper {
    // The unique row serializes first saves as well as updates, without an app_user exclusive lock.
    // app_user's foreign-key shared lock remains compatible with candidate confirmation inserts.
    @Insert("INSERT INTO card_organization_lock(user_id) VALUES(#{userId}) ON DUPLICATE KEY UPDATE user_id=#{userId}")
    int ensureRow(long userId);

    @Select("SELECT user_id FROM card_organization_lock WHERE user_id=#{userId} FOR UPDATE")
    Long lockByUserId(long userId);
}
