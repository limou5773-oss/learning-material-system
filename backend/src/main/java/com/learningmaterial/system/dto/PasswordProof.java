package com.learningmaterial.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordProof(@NotBlank @Size(max = 64) String currentPassword) { }
