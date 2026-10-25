package com.learningmaterial.system.card;

import com.learningmaterial.system.auth.AuthPrincipal;
import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cards")
public class CardController {
    private final CardService cards;
    public CardController(CardService cards) { this.cards = cards; }

    @GetMapping
    public ApiResponse<PageView<CardService.CardView>> list(@AuthenticationPrincipal AuthPrincipal principal,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
        HttpServletRequest request) {
        return ApiResponse.of(cards.list(principal.userId(), page, pageSize), request);
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
