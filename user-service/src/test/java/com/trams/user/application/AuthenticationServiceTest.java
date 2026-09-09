package com.trams.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trams.user.domain.AccountDisabledException;
import com.trams.user.domain.InvalidCredentialsException;
import com.trams.user.domain.InvalidRefreshTokenException;
import com.trams.user.domain.User;
import com.trams.user.infrastructure.persistence.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Tests authentication, with emphasis on the properties that resist account discovery. */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    private static final RefreshTokenService.ClientContext CLIENT =
            new RefreshTokenService.ClientContext("203.0.113.7", "curl/8");

    @Mock private UserRepository users;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AccessTokenIssuer accessTokens;
    @Mock private RefreshTokenService refreshTokens;
    @Mock private TokenRevocationService revocations;

    private AuthenticationService authentication;

    @BeforeEach
    void setUp() {
        // The constructor hashes a dummy value to have a realistic hash available for
        // the unknown-account path; see equalisesWorkForUnknownAccounts below.
        when(passwordEncoder.encode(anyString())).thenReturn("{argon2}$dummy");

        authentication =
                new AuthenticationService(users, passwordEncoder, accessTokens, refreshTokens, revocations);
    }

    private static User activeUser() {
        return User.register("someone@example.com", "{argon2}$real", "Someone", Instant.now());
    }

    @Test
    @DisplayName("valid credentials yield an access token and a refresh token")
    void loginSucceeds() {
        User user = activeUser();
        when(users.findByEmail("someone@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "{argon2}$real")).thenReturn(true);
        when(accessTokens.issue(user))
                .thenReturn(
                        new AccessTokenIssuer.IssuedAccessToken("access-jwt", Instant.now().plusSeconds(900), 900));
        when(refreshTokens.issue(eq(user.getId()), any()))
                .thenReturn(
                        new RefreshTokenService.IssuedRefreshToken(
                                "refresh-token", Instant.now().plusSeconds(2_592_000)));

        var result = authentication.login("someone@example.com", "correct-password", CLIENT);

        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.user()).isEqualTo(user);
    }

    @Test
    @DisplayName("login normalises the submitted email before lookup")
    void normalisesEmailOnLogin() {
        // Otherwise a user who registered as "Someone@Example.com" could not sign in
        // with the lower-case form, or vice versa.
        when(users.findByEmail("someone@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authentication.login("  SOMEONE@EXAMPLE.COM ", "pw", CLIENT))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(users).findByEmail("someone@example.com");
    }

    @Test
    @DisplayName("a wrong password is rejected as invalid credentials")
    void rejectsWrongPassword() {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(activeUser()));
        when(passwordEncoder.matches("wrong", "{argon2}$real")).thenReturn(false);

        assertThatThrownBy(() -> authentication.login("someone@example.com", "wrong", CLIENT))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokens, never()).issue(any(), any());
    }

    @Test
    @DisplayName("an unknown account still performs a password verification")
    void equalisesWorkForUnknownAccounts() {
        // This is the anti-enumeration property.
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("any-password", "{argon2}$dummy")).thenReturn(false);

        assertThatThrownBy(() -> authentication.login("nobody@example.com", "any-password", CLIENT))
                .isInstanceOf(InvalidCredentialsException.class);

        // The expensive hash comparison ran even though no account exists.
        verify(passwordEncoder, times(1)).matches("any-password", "{argon2}$dummy");
    }

    @Test
    @DisplayName("a disabled account is refused only after its password is verified")
    void refusesDisabledAccountsAfterVerifying() {
        // Checking status before the password would let an attacker distinguish
        // "disabled account exists" from "no account", which is still enumeration.
        User user = activeUser();
        user.disable(Instant.now());

        when(users.findByEmail(anyString())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "{argon2}$real")).thenReturn(true);

        assertThatThrownBy(() -> authentication.login("someone@example.com", "correct-password", CLIENT))
                .isInstanceOf(AccountDisabledException.class);

        verify(passwordEncoder).matches("correct-password", "{argon2}$real");
        verify(refreshTokens, never()).issue(any(), any());
    }

    @Test
    @DisplayName("refresh rotates the token and issues a new access token")
    void refreshRotates() {
        User user = activeUser();
        var replacement =
                new RefreshTokenService.IssuedRefreshToken("new-refresh", Instant.now().plusSeconds(2_592_000));

        when(refreshTokens.rotate(eq("old-refresh"), any()))
                .thenReturn(new RefreshTokenService.RotationResult(user.getId(), replacement));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(accessTokens.issue(user))
                .thenReturn(
                        new AccessTokenIssuer.IssuedAccessToken("new-access", Instant.now().plusSeconds(900), 900));

        var result = authentication.refresh("old-refresh", CLIENT);

        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    @DisplayName("refreshing for a deleted account fails instead of minting a token")
    void refreshFailsForDeletedAccount() {
        UUID userId = UUID.randomUUID();
        when(refreshTokens.rotate(anyString(), any()))
                .thenReturn(
                        new RefreshTokenService.RotationResult(
                                userId,
                                new RefreshTokenService.IssuedRefreshToken("x", Instant.now().plusSeconds(60))));
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authentication.refresh("some-token", CLIENT))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("refreshing a disabled account also revokes its remaining sessions")
    void refreshRevokesSessionsForDisabledAccount() {
        // Disabling an account must end existing sessions, not merely block new logins;
        // otherwise a live refresh token would keep working indefinitely.
        User user = activeUser();
        user.disable(Instant.now());

        when(refreshTokens.rotate(anyString(), any()))
                .thenReturn(
                        new RefreshTokenService.RotationResult(
                                user.getId(),
                                new RefreshTokenService.IssuedRefreshToken("x", Instant.now().plusSeconds(60))));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authentication.refresh("some-token", CLIENT))
                .isInstanceOf(AccountDisabledException.class);

        verify(revocations).revokeAllSessionsImmediately(user.getId(), "account-disabled");
    }

    @Test
    @DisplayName("logout revokes the presented refresh token")
    void logoutRevokes() {
        authentication.logout("a-refresh-token");

        verify(refreshTokens).revoke("a-refresh-token");
    }
}
