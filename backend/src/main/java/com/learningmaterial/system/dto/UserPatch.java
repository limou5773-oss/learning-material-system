package com.learningmaterial.system.dto;

import jakarta.validation.constraints.Size;

public record UserPatch(
        @Size(min = 1, max = 64) String displayName,
        @Size(max = 64) String currentPassword,
        @Size(min = 8, max = 64) String newPassword) {
    public UserPatch {
        if (displayName != null) displayName = displayName.strip();
    }
}
