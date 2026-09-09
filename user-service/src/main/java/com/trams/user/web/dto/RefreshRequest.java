package com.trams.user.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
        @NotBlank(message = "must not be blank") @Size(max = 512) String refreshToken) {}
