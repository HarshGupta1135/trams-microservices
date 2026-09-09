package com.trams.user.application;

import com.trams.contracts.UserEventPayload;
import com.trams.user.domain.EmailAlreadyRegisteredException;
import com.trams.user.domain.Role;
import com.trams.user.domain.User;
import com.trams.user.infrastructure.persistence.UserRepository;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers new accounts. */
@Service
public class UserRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final OutboxRecorder outbox;

    public UserRegistrationService(
            UserRepository users, PasswordEncoder passwordEncoder, OutboxRecorder outbox) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.outbox = outbox;
    }

    @Transactional
    public User register(String email, String rawPassword, String fullName) {
        String normalisedEmail = User.normaliseEmail(email);

        // Cheap pre-check for the ordinary case.
        if (users.existsByEmail(normalisedEmail)) {
            throw new EmailAlreadyRegisteredException();
        }

        Instant now = Instant.now();
        User user = User.register(normalisedEmail, passwordEncoder.encode(rawPassword), fullName, now);

        try {
            // Flush now so a unique-constraint violation surfaces here, where it can be
            // translated, rather than at commit time where it would escape as a 500.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent registration of the same address.
            log.debug("Concurrent registration detected for an existing address");
            throw new EmailAlreadyRegisteredException();
        }

        Set<String> roleNames = user.getRoles().stream().map(Role::name).collect(Collectors.toSet());

        outbox.record(
                new UserEventPayload.UserRegistered(
                        user.getId(), user.getEmail(), user.getFullName(), roleNames, now),
                now);

        log.info("Registered user {}", user.getId());

        return user;
    }
}
