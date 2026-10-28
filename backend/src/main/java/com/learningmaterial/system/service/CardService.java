package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.CardDtos;
import com.learningmaterial.system.dto.CardSourceList;
import com.learningmaterial.system.dto.CardView;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.entity.CardRow;

/** CardService 的业务契约。 */
public interface CardService {
    PageView<CardView> list(long userId, int page, int pageSize);

    PageView<CardView> list(long userId, String keyword, String subject, String tag,
                                   boolean favoriteOnly, int page, int pageSize);

    CardView create(long userId, CardDtos.CardInput input);

    CardView patch(long userId, String id, CardDtos.CardPatch input);

    CardView get(long userId, String id);

    void delete(long userId, String id, int version);

    CardSourceList sources(long userId, String id);

    CardView view(CardRow row);

}
