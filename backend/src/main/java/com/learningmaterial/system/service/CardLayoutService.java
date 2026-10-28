package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.CardLayoutDtos;

/** CardLayoutService 的业务契约。 */
public interface CardLayoutService {
    CardLayoutDtos.LayoutView get(long userId, String key);

    CardLayoutDtos.LayoutView save(long userId, String key, CardLayoutDtos.LayoutInput input);

}
