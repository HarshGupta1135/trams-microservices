package com.trams.user.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login payload.
 *
 * <p>Note the absence of an {@code @Email} constraint. Rejecting a malformed address here
 * with a validation error would distinguish "not an email" from "wrong credentials",
 * giving an attacker a free signal; every failure on this endpoint should look identical.
 */
public record LoginRequest(
        @NotBlank(message = "must not be blank") @Size(max = 320) String email,
        @NotBlank(message = "must not be blank") @Size(max = 128) String password) {}
