package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.CardDtos;
import com.learningmaterial.system.dto.CardView;

/** CardHierarchyService 的业务契约。 */
public interface CardHierarchyService {
    CardView setParent(long userId, String id, CardDtos.ParentInput input);

}
