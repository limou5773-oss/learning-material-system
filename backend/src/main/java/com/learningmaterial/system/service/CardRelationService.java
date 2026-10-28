package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.CardRelationDtos;
import com.learningmaterial.system.dto.MaterialDtos.PageView;
import com.learningmaterial.system.entity.CardRelationRow;
import com.learningmaterial.system.entity.CardRow;

/** CardRelationService 的业务契约。 */
public interface CardRelationService {
    CardRelationDtos.RelationView create(long userId, CardRelationDtos.RelationInput input);

    PageView<CardRelationDtos.RelationView> list(long userId, String id, int page, int pageSize);

    void delete(long userId, String id);

    CardRow requireOwned(long userId, String id);

    CardRelationDtos.RelationView view(CardRelationRow row);

}
