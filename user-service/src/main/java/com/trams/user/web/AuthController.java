package com.trams.user.web;

import com.trams.user.application.AuthenticationService;
import com.trams.user.application.RefreshTokenService.ClientContext;
import com.trams.user.application.UserRegistrationService;
import com.trams.user.domain.User;
import com.trams.user.web.dto.LoginRequest;
import com.trams.user.web.dto.RefreshRequest;
import com.trams.user.web.dto.RegisterRequest;
import com.trams.user.web.dto.TokenResponse;
import com.trams.user.web.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated endpoints: registration and the token lifecycle. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, login, token refresh and logout")
public class AuthController {

    private final UserRegistrationService registrations;
    private final AuthenticationService authentication;

    public AuthController(UserRegistrationService registrations, AuthenticationService authentication) {
        this.registrations = registrations;
        this.authentication = authentication;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Register a new account",
            description =
                    """
                    Creates an account and emits a `user.registered` event through the transactional
                    outbox, which the Notification Service consumes to send a welcome message.

                    The account and the event are committed atomically, so a welcome notification is
                    never sent for a registration that failed.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Account created"),
        @ApiResponse(responseCode = "400", description = "Validation failed", content = {}),
        @ApiResponse(responseCode = "409", description = "Email address already registered", content = {})
    })
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = registrations.register(request.email(), request.password(), request.fullName());
        return UserResponse.from(user);
    }

    @PostMapping("/login")
    @Operation(
            summary = "Exchange credentials for a token pair",
            description =
                    """
                    Returns a short-lived access token and a long-lived, single-use refresh token.

                    An unknown email and a wrong password produce an identical response, so this
                    endpoint cannot be used to discover which addresses have accounts.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Authenticated"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials", content = {}),
        @ApiResponse(responseCode = "403", description = "Account disabled", content = {})
    })
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        ClientContext client = ClientContextResolver.resolve(httpRequest);

        return TokenResponse.from(authentication.login(request.email(), request.password(), client));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Exchange a refresh token for a new token pair",
            description =
                    """
                    Rotates the refresh token: the presented token is consumed and a replacement is
                    issued. Presenting an already-consumed token indicates replay, and revokes every
                    token in that family.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "New token pair issued"),
        @ApiResponse(responseCode = "401", description = "Refresh token unknown, expired or replayed", content = {})
    })
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        ClientContext client = ClientContextResolver.resolve(httpRequest);

        return TokenResponse.from(authentication.refresh(request.refreshToken(), client));
    }

    @PostMapping("/logout")
    @Operation(
            summary = "Revoke a refresh token",
            description =
                    """
                    Ends the session. Always returns 204, including for a token that does not exist,
                    so the endpoint reveals nothing about which token values are valid.
                    """)
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authentication.logout(request.refreshToken());

        return ResponseEntity.noContent().build();
    }
}
