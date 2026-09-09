package com.trams.user.application;

import com.trams.user.domain.AccountDisabledException;
import com.trams.user.domain.InvalidCredentialsException;
import com.trams.user.domain.InvalidRefreshTokenException;
import com.trams.user.domain.User;
import com.trams.user.infrastructure.persistence.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticates users and manages their sessions.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenService refreshTokens;
    private final TokenRevocationService revocations;

    /**
     * A valid hash of a value nobody knows, used to spend the same CPU time verifying a
     * password for an address that does not exist. See {@link #login}.
     */
    private final String dummyHash;

    public AuthenticationService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokens,
            RefreshTokenService refreshTokens,
            TokenRevocationService revocations) {

        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.revocations = revocations;
        this.dummyHash = passwordEncoder.encode("a-password-that-is-never-valid");
    }

    /** The token pair returned by a successful login or refresh. */
    public record AuthenticationResult(
            String accessToken,
            Instant accessTokenExpiresAt,
            long expiresInSeconds,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            User user) {}

    /**
     * Verifies credentials and starts a session.
     *
     * <p><strong>On timing.</strong> When the address is unknown, the password is still
     * verified against a dummy hash before failing. Returning early would make the
     * "no such user" path measurably faster than the "wrong password" path, turning
     * response time into an account-enumeration oracle — which would defeat the point of
     * returning an identical error message for both.
     *
     * @throws InvalidCredentialsException for an unknown address or a wrong password
     * @throws AccountDisabledException if the account exists but is disabled
     */
    @Transactional
    public AuthenticationResult login(
            String email, String rawPassword, RefreshTokenService.ClientContext client) {

        Optional<User> candidate = users.findByEmail(User.normaliseEmail(email));

        String storedHash = candidate.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(rawPassword, storedHash);

        if (candidate.isEmpty() || !passwordMatches) {
            log.info("Failed login attempt from {}", client.ipAddress());
            throw new InvalidCredentialsException();
        }

        User user = candidate.get();

        // Checked only after the password is confirmed, so the response cannot reveal
        // that a given address exists but is disabled.
        if (!user.isActive()) {
            throw new AccountDisabledException();
        }

        log.info("User {} authenticated", user.getId());

        return issueSession(user, client);
    }

    /**
     * Exchanges a refresh token for a new token pair.
     *
     * <p>Rotation happens in {@link RefreshTokenService#rotate}, which also detects
     * replay of an already-consumed token.
     *
     * @throws InvalidRefreshTokenException if the token is unknown, expired or replayed
     */
    @Transactional
    public AuthenticationResult refresh(String refreshToken, RefreshTokenService.ClientContext client) {
        RefreshTokenService.RotationResult rotation = refreshTokens.rotate(refreshToken, client);

        User user =
                users.findById(rotation.userId())
                        .orElseThrow(() -> new InvalidRefreshTokenException("the account no longer exists"));

        // Re-checked on every refresh: disabling an account must end its sessions, not
        // merely prevent new logins.
        if (!user.isActive()) {
            // Independent transaction, for the same reason as reuse detection: the
            // exception below would otherwise roll back the revocation.
            revocations.revokeAllSessionsImmediately(user.getId(), "account-disabled");
            throw new AccountDisabledException();
        }

        AccessTokenIssuer.IssuedAccessToken access = accessTokens.issue(user);

        return new AuthenticationResult(
                access.token(),
                access.expiresAt(),
                access.expiresInSeconds(),
                rotation.replacement().token(),
                rotation.replacement().expiresAt(),
                user);
    }

    /** Ends the session associated with the presented refresh token. */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    private AuthenticationResult issueSession(User user, RefreshTokenService.ClientContext client) {
        AccessTokenIssuer.IssuedAccessToken access = accessTokens.issue(user);
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokens.issue(user.getId(), client);

        return new AuthenticationResult(
                access.token(),
                access.expiresAt(),
                access.expiresInSeconds(),
                refresh.token(),
                refresh.expiresAt(),
                user);
    }
}
