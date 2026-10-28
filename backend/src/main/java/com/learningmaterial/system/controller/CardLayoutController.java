package com.learningmaterial.system.controller;

import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.dto.CardLayoutDtos;
import com.learningmaterial.system.security.AuthPrincipal;
import com.learningmaterial.system.service.CardLayoutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/card-layouts")
public class CardLayoutController {
    private final CardLayoutService layouts;
    public CardLayoutController(CardLayoutService layouts) { this.layouts = layouts; }
    @GetMapping("/{layoutKey}")
    public ApiResponse<CardLayoutDtos.LayoutView> get(@AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable String layoutKey, HttpServletRequest request) {
        return ApiResponse.of(layouts.get(principal.userId(), layoutKey), request);
    }
    @PutMapping("/{layoutKey}")
    public ApiResponse<CardLayoutDtos.LayoutView> save(@AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable String layoutKey, @Valid @RequestBody CardLayoutDtos.LayoutInput input, HttpServletRequest request) {
        return ApiResponse.of(layouts.save(principal.userId(), layoutKey, input), request);
    }
}
