package com.learningmaterial.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WechatCodeInput(@NotBlank @Size(max = 256) String code) { }
