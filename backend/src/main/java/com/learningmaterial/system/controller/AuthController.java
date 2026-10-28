package com.learningmaterial.system.controller;

import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.dto.AuthDtos.LoginInput;
import com.learningmaterial.system.dto.AuthDtos.RegistrationInput;
import com.learningmaterial.system.dto.AuthDtos.SessionToken;
import com.learningmaterial.system.dto.AuthDtos.UserView;
import com.learningmaterial.system.security.AuthPrincipal;
import com.learningmaterial.system.dto.UserPatch;
import com.learningmaterial.system.dto.WechatCodeInput;
import com.learningmaterial.system.dto.PasswordProof;
import org.springframework.web.bind.annotation.PatchMapping;
import com.learningmaterial.system.service.AuthService;
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
    @PatchMapping("/users/me")
    public ApiResponse<UserView> patchMe(@AuthenticationPrincipal AuthPrincipal principal,
                                         @Valid @RequestBody UserPatch input, HttpServletRequest request) {
        return ApiResponse.of(auth.updateProfile(principal, input), request);
    }

    @PostMapping("/auth/wechat-sessions")
    public ResponseEntity<ApiResponse<SessionToken>> loginWechat(@Valid @RequestBody WechatCodeInput input,
                                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(auth.loginWechat(input.code()), request));
    }

    @PostMapping("/users/me/wechat-binding")
    public ResponseEntity<ApiResponse<UserView>> bindWechat(@AuthenticationPrincipal AuthPrincipal principal,
                                                            @Valid @RequestBody WechatCodeInput input,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(auth.bindWechat(principal, input.code()), request));
    }

    @DeleteMapping("/users/me/wechat-binding")
    public ResponseEntity<Void> unbindWechat(@AuthenticationPrincipal AuthPrincipal principal,
                                             @Valid @RequestBody PasswordProof input) {
        auth.unbindWechat(principal, input.currentPassword());
        return ResponseEntity.noContent().build();
    }

}
