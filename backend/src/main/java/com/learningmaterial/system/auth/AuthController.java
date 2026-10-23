package com.learningmaterial.system.auth;

import com.learningmaterial.system.auth.AuthDtos.LoginInput;
import com.learningmaterial.system.auth.AuthDtos.RegistrationInput;
import com.learningmaterial.system.auth.AuthDtos.SessionToken;
import com.learningmaterial.system.auth.AuthDtos.UserView;
import com.learningmaterial.system.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/auth/registrations")
    public ResponseEntity<ApiResponse<UserView>> register(@Valid @RequestBody RegistrationInput input,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(auth.register(input), request));
    }

    @PostMapping("/auth/sessions")
    public ResponseEntity<ApiResponse<SessionToken>> login(@Valid @RequestBody LoginInput input,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(auth.login(input), request));
    }

    @DeleteMapping("/auth/sessions/current")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthPrincipal principal) {
        auth.logout(principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/me")
    public ApiResponse<UserView> me(@AuthenticationPrincipal AuthPrincipal principal,
                                    HttpServletRequest request) {
        return ApiResponse.of(auth.currentUser(principal), request);
    }
}
