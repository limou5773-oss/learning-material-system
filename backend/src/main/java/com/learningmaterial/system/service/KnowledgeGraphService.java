package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.CardRelationDtos;

/** KnowledgeGraphService 的业务契约。 */
public interface KnowledgeGraphService {
    CardRelationDtos.GraphView get(long userId, String subject, String rootId);

}
