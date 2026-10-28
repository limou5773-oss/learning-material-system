package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.FavoriteView;
import com.learningmaterial.system.dto.MaterialDtos.PageView;

/** FavoriteService 的业务契约。 */
public interface FavoriteService {
    void add(long userId, String type, String id);

    void remove(long userId, String type, String id);

    PageView<FavoriteView> list(long userId, String type, int page, int pageSize);

}
