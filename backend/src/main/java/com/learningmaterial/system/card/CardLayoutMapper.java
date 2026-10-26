package com.learningmaterial.system.card;

import org.apache.ibatis.annotations.*;

@Mapper
public interface CardLayoutMapper {
    @Select("SELECT * FROM card_layout WHERE user_id=#{userId} AND layout_key=#{key}")
    CardLayoutRow findOwned(@Param("userId") long userId, @Param("key") String key);

    @Select("SELECT * FROM card_layout WHERE user_id=#{userId} AND layout_key=#{key} FOR UPDATE")
    CardLayoutRow findOwnedForUpdate(@Param("userId") long userId, @Param("key") String key);

    @Insert("INSERT INTO card_layout(user_id,layout_key,items_json,version,created_at,updated_at) VALUES(#{userId},#{layoutKey},#{itemsJson},#{version},#{createdAt},#{updatedAt})")
    int insert(CardLayoutRow row);

    @Update("UPDATE card_layout SET items_json=#{itemsJson},version=#{version},updated_at=#{updatedAt} WHERE id=#{id} AND user_id=#{userId}")
    int update(CardLayoutRow row);
}
