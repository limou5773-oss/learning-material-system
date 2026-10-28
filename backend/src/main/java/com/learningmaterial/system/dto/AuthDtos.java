package com.learningmaterial.system.dto;

import com.learningmaterial.system.entity.UserRow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.ZoneOffset;

public final class AuthDtos {
    private AuthDtos() { }

    public record RegistrationInput(
        @NotBlank @Size(min = 6, max = 32) @Pattern(regexp = "[A-Za-z0-9_]+") String username,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank @Size(max = 64) String displayName
    ) { }

    public enum ClientType { WEB, MINIPROGRAM }

    public record LoginInput(
        @NotBlank String username,
        @NotBlank String password,
        @NotNull ClientType clientType
    ) { }

    public record UserView(
        String id, String username, String displayName, String role,
        String status, boolean hasWechatBinding, Instant createdAt
    ) {
        public static UserView from(UserRow row) {
            return new UserView(row.getId().toString(), row.getUsername(), row.getDisplayName(),
                row.getRole(), row.getStatus(), row.isWechatBound(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC));
        }
    }

    public record SessionToken(
        String accessToken, String tokenType, Instant expiresAt, UserView user
    ) { }
}
