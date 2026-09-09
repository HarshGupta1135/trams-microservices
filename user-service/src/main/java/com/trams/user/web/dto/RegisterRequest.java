package com.trams.user.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration payload.
 *
 * <p>The password policy favours length over composition rules, following current NIST
 * guidance: a 12-character minimum with no forced symbol classes produces stronger
 * passwords in practice than short passwords mangled to satisfy a character-class rule.
 * The upper bound exists purely to cap hashing cost - Argon2 work is proportional to
 * input, so an unbounded password field is a denial-of-service vector.
 */
public record RegisterRequest(
        @NotBlank(message = "must not be blank")
                @Email(message = "must be a well-formed email address")
                @Size(max = 320, message = "must be at most 320 characters")
                String email,
        @NotBlank(message = "must not be blank")
                @Size(min = 12, max = 128, message = "must be between 12 and 128 characters")
                String password,
        @NotBlank(message = "must not be blank")
                @Size(max = 200, message = "must be at most 200 characters")
                String fullName) {}
