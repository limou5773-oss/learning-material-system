package com.learningmaterial.system.material;

import com.learningmaterial.system.auth.AuthPrincipal;
import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import com.learningmaterial.system.material.FavoriteService.FavoriteView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/favorites")
public class FavoriteController {
    private final FavoriteService favorites;

    public FavoriteController(FavoriteService favorites) { this.favorites = favorites; }

    @PutMapping("/{type}/{id}")
    public ResponseEntity<Void> add(@AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable String type, @PathVariable String id) {
        favorites.add(principal.userId(), type, id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{type}/{id}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable String type, @PathVariable String id) {
        favorites.remove(principal.userId(), type, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ApiResponse<PageView<FavoriteView>> list(@AuthenticationPrincipal AuthPrincipal principal,
                                                     @RequestParam(required = false) String type,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int pageSize,
                                                     HttpServletRequest request) {
        return ApiResponse.of(favorites.list(principal.userId(), type, page, pageSize), request);
    }
}
