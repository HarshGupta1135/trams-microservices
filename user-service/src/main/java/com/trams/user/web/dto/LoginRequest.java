package com.trams.user.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Login payload. */
public record LoginRequest(
        @NotBlank(message = "must not be blank") @Size(max = 320) String email,
        @NotBlank(message = "must not be blank") @Size(max = 128) String password) {}
