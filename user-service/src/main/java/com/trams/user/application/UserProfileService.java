package com.trams.user.application;

import com.trams.contracts.UserEventPayload;
import com.trams.user.domain.EmailAlreadyRegisteredException;
import com.trams.user.domain.InvalidCredentialsException;
import com.trams.user.domain.User;
import com.trams.user.domain.UserNotFoundException;
import com.trams.user.infrastructure.persistence.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and modifies user profiles.
 *
 * <p>Every mutation records its event in the same transaction as the change itself, so the
 * notification a user receives always corresponds to a change that actually committed.
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final OutboxRecorder outbox;

    public UserProfileService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokens,
            OutboxRecorder outbox) {

        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public User requireById(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    @Transactional(readOnly = true)
    public Page<User> list(Pageable pageable) {
        return users.findAll(pageable);
    }

    /**
     * Applies a partial profile update. Null fields are left untouched.
     *
     * <p>No event is emitted when nothing actually changed: a client that re-submits an
     * unchanged form should not generate a notification.
     *
     * @throws EmailAlreadyRegisteredException if the new address belongs to someone else
     */
    @Transactional
    public User updateProfile(UUID userId, String fullName, String email) {
        User user = requireById(userId);
        Instant now = Instant.now();

        if (email != null) {
            String normalised = User.normaliseEmail(email);
            // Only a *different* user holding the address is a conflict; re-submitting
            // one's own address is a no-op.
            if (!normalised.equals(user.getEmail()) && users.existsByEmail(normalised)) {
                throw new EmailAlreadyRegisteredException();
            }
        }

        List<String> changedFields = user.updateProfile(fullName, email, now);

        if (changedFields.isEmpty()) {
            log.debug("Profile update for user {} changed nothing; no event emitted", userId);
            return user;
        }

        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyRegisteredException();
        }

        outbox.record(
                new UserEventPayload.UserProfileUpdated(
                        user.getId(), user.getEmail(), user.getFullName(), changedFields, now),
                now);

        log.info("Updated profile fields {} for user {}", changedFields, userId);

        return user;
    }

    /**
     * Changes a password after verifying the current one.
     *
     * <p>Re-authentication is required even though the caller already holds a valid access
     * token: it is what stops a stolen token from being escalated into permanent account
     * takeover.
     *
     * <p>All other sessions are then revoked, which is the point of the operation from a
     * security standpoint — if the password is being changed because it was compromised,
     * leaving the attacker's refresh token alive would defeat the exercise.
     *
     * @throws InvalidCredentialsException if the current password is wrong
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword, String requestIp) {
        User user = requireById(userId);

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            log.info("Rejected password change for user {}: current password did not match", userId);
            throw new InvalidCredentialsException();
        }

        Instant now = Instant.now();
        user.changePassword(passwordEncoder.encode(newPassword), now);
        users.save(user);

        refreshTokens.revokeAllSessions(userId, "password-changed");

        outbox.record(
                new UserEventPayload.UserPasswordChanged(
                        user.getId(), user.getEmail(), user.getFullName(), requestIp, now),
                now);

        log.info("Password changed for user {}; all sessions revoked", userId);
    }

    /**
     * Deletes an account.
     *
     * <p>The event is recorded before the row is removed, while the address and name are
     * still available — the Notification Service needs them to send the confirmation, and
     * by design it cannot call back to ask.
     */
    @Transactional
    public void delete(UUID userId) {
        User user = requireById(userId);
        Instant now = Instant.now();

        outbox.record(
                new UserEventPayload.UserDeleted(
                        user.getId(), user.getEmail(), user.getFullName(), now),
                now);

        // Roles and refresh tokens are removed by ON DELETE CASCADE.
        users.delete(user);

        log.info("Deleted user {}", userId);
    }
}
