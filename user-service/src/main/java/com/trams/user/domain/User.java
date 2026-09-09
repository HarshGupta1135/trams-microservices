package com.trams.user.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A registered account. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;

    /**
     * Eagerly fetched: the role set is tiny, and it is needed on essentially every read of a
     * user (to mint a token or authorise a request), so lazy loading would.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    /** Required by JPA; not for application use. */
    protected User() {}

    private User(UUID id, String email, String passwordHash, String fullName, Set<Role> roles, Instant now) {
        this.id = id;
        this.email = normaliseEmail(email);
        this.passwordHash = passwordHash;
        this.fullName = fullName.strip();
        this.roles = EnumSet.copyOf(roles);
        this.status = UserStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Registers a new active user holding the Role#USER role. */
    public static User register(String email, String passwordHash, String fullName, Instant now) {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(fullName, "fullName");

        return new User(UUID.randomUUID(), email, passwordHash, fullName, EnumSet.of(Role.USER), now);
    }

    /** Lower-cases and trims an address so that uniqueness is case-insensitive. */
    public static String normaliseEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /** Applies a profile change. */
    public List<String> updateProfile(String newFullName, String newEmail, Instant now) {
        List<String> changed = new ArrayList<>(2);

        if (newFullName != null) {
            String candidate = newFullName.strip();
            if (!candidate.equals(this.fullName)) {
                this.fullName = candidate;
                changed.add("fullName");
            }
        }

        if (newEmail != null) {
            String candidate = normaliseEmail(newEmail);
            if (!candidate.equals(this.email)) {
                this.email = candidate;
                changed.add("email");
            }
        }

        if (!changed.isEmpty()) {
            this.updatedAt = now;
        }

        return Collections.unmodifiableList(changed);
    }

    public void changePassword(String newPasswordHash, Instant now) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "newPasswordHash");
        this.updatedAt = now;
    }

    public void grant(Role role) {
        this.roles.add(role);
    }

    public void disable(Instant now) {
        this.status = UserStatus.DISABLED;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    @PreUpdate
    void touch() {
        // Safety net for any path that mutates state without stamping the time itself.
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    /** Identity is the primary key alone. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof User user)) return false;
        return id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }

    /** Deliberately excludes the password hash so it cannot leak into a log line. */
    @Override
    public String toString() {
        return "User[id=%s, email=%s, status=%s, roles=%s]".formatted(id, email, status, roles);
    }
}
