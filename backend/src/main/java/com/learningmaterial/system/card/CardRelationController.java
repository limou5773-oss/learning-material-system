package com.learningmaterial.system.card;

import com.learningmaterial.system.auth.AuthPrincipal;
import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CardRelationController {
    private final CardRelationService relations;
    private final KnowledgeGraphService graphs;
    public CardRelationController(CardRelationService relations, KnowledgeGraphService graphs) { this.relations = relations; this.graphs = graphs; }

    @PostMapping("/card-relations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CardRelationDtos.RelationView> create(@AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody CardRelationDtos.RelationInput input, HttpServletRequest request) {
        return ApiResponse.of(relations.create(principal.userId(), input), request);
    }

    @GetMapping("/card-relations")
    public ApiResponse<PageView<CardRelationDtos.RelationView>> list(@AuthenticationPrincipal AuthPrincipal principal,
        @RequestParam(required = false) String cardId, @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize, HttpServletRequest request) {
        return ApiResponse.of(relations.list(principal.userId(), cardId, page, pageSize), request);
    }

    @DeleteMapping("/card-relations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) { relations.delete(principal.userId(), id); }

    @GetMapping("/knowledge-graphs")
    public ApiResponse<CardRelationDtos.GraphView> graph(@AuthenticationPrincipal AuthPrincipal principal,
        @RequestParam(required = false) String subject, @RequestParam(required = false) String rootCardId, HttpServletRequest request) {
        return ApiResponse.of(graphs.get(principal.userId(), subject, rootCardId), request);
    }
}
