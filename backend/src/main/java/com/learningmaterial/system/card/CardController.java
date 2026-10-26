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
@RequestMapping("/api/v1/cards")
public class CardController {
    private final CardService cards;
    private final CardHierarchyService hierarchy;
    public CardController(CardService cards, CardHierarchyService hierarchy) { this.cards = cards; this.hierarchy = hierarchy; }

    @PutMapping("/{id}/parent")
    public ApiResponse<CardService.CardView> setParent(@AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable String id, @Valid @RequestBody CardDtos.ParentInput input, HttpServletRequest request) {
        return ApiResponse.of(hierarchy.setParent(principal.userId(), id, input), request);
    }

    @GetMapping
    public ApiResponse<PageView<CardService.CardView>> list(@AuthenticationPrincipal AuthPrincipal principal,
        @RequestParam(required = false) String keyword, @RequestParam(required = false) String subject,
        @RequestParam(required = false) String tag, @RequestParam(defaultValue = "false") boolean favoriteOnly,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
        HttpServletRequest request) {
        return ApiResponse.of(cards.list(principal.userId(), keyword, subject, tag, favoriteOnly, page, pageSize), request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CardService.CardView> create(@AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody CardDtos.CardInput input, HttpServletRequest request) {
        return ApiResponse.of(cards.create(principal.userId(), input), request);
    }

    @PatchMapping("/{id}")
    public ApiResponse<CardService.CardView> patch(@AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable String id, @Valid @RequestBody CardDtos.CardPatch input, HttpServletRequest request) {
        return ApiResponse.of(cards.patch(principal.userId(), id, input), request);
    }

    @GetMapping("/{id}")
    public ApiResponse<CardService.CardView> get(@AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable String id, HttpServletRequest request) {
        return ApiResponse.of(cards.get(principal.userId(), id), request);
    }

    @GetMapping("/{id}/sources")
    public ApiResponse<CardService.SourceList> sources(@AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable String id, HttpServletRequest request) {
        return ApiResponse.of(cards.sources(principal.userId(), id), request);
    }
}
