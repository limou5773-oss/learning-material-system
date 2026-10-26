package com.learningmaterial.system.card;

import com.learningmaterial.system.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class KnowledgeGraphService {
    private static final int NODE_LIMIT = 200, EDGE_LIMIT = 600;
    private final CardRelationMapper relations;
    private final CardRelationService relationService;
    private final CardService cardService;
    public KnowledgeGraphService(CardRelationMapper relations, CardRelationService relationService, CardService cardService) {
        this.relations = relations; this.relationService = relationService; this.cardService = cardService;
    }

    @Transactional(readOnly = true)
    public CardRelationDtos.GraphView get(long userId, String subject, String rootId) {
        Long root = rootId == null ? null : relationService.requireOwned(userId, rootId).getId();
        subject = subject == null || subject.isBlank() ? null : subject.trim();
        if (subject != null && subject.length() > 64)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", "学科超过长度上限");
        // Compare subjects in SQL so root validation and node filtering share the database collation.
        if (root != null && subject != null && relations.countRootSubject(userId, root, subject) == 0)
            return new CardRelationDtos.GraphView(List.of(), List.of(), false);
        List<CardRow> candidates = relations.graphNodes(userId, subject, root, NODE_LIMIT + 1);
        List<CardRow> nodes = candidates.subList(0, Math.min(candidates.size(), NODE_LIMIT));
        List<CardRelationRow> edges = nodes.isEmpty() ? List.of()
            : relations.graphEdges(userId, nodes.stream().map(CardRow::getId).toList(), EDGE_LIMIT + 1);
        return new CardRelationDtos.GraphView(nodes.stream().map(cardService::view).toList(),
            edges.stream().limit(EDGE_LIMIT).map(CardRelationService::view).toList(),
            candidates.size() > NODE_LIMIT || edges.size() > EDGE_LIMIT);
    }
}
