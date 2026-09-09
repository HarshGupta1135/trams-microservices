package com.trams.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests the invariants the User entity owns. */
class UserTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");
    private static final Instant LATER = NOW.plusSeconds(3600);

    private static User newUser() {
        return User.register("Someone@Example.COM", "{argon2}$hash", "Someone Example", NOW);
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("normalises the email address so uniqueness is case-insensitive")
        void normalisesEmail() {
            // Without this, "A@x.com" and "a@x.com" would be two accounts, and a user
            // could be locked out by signing up with different capitalisation.
            assertThat(newUser().getEmail()).isEqualTo("someone@example.com");
        }

        @Test
        @DisplayName("trims surrounding whitespace from the email and name")
        void trimsWhitespace() {
            User user = User.register("  spaced@example.com  ", "{argon2}$h", "  Padded Name  ", NOW);

            assertThat(user.getEmail()).isEqualTo("spaced@example.com");
            assertThat(user.getFullName()).isEqualTo("Padded Name");
        }

        @Test
        @DisplayName("starts active with only the USER role")
        void startsActiveAsPlainUser() {
            User user = newUser();

            assertThat(user.isActive()).isTrue();
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            // Registration must never confer ADMIN; privilege is granted deliberately.
            assertThat(user.getRoles()).containsExactly(Role.USER);
        }

        @Test
        @DisplayName("assigns an id and matching timestamps")
        void assignsIdAndTimestamps() {
            User user = newUser();

            assertThat(user.getId()).isNotNull();
            assertThat(user.getCreatedAt()).isEqualTo(NOW);
            assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("profile updates")
    class ProfileUpdates {

        @Test
        @DisplayName("reports exactly which fields changed")
        void reportsChangedFields() {
            // The caller uses this list to describe the change in the event it emits, so
            // it has to be accurate rather than merely non-empty.
            User user = newUser();

            var changed = user.updateProfile("New Name", "new@example.com", LATER);

            assertThat(changed).containsExactly("fullName", "email");
            assertThat(user.getFullName()).isEqualTo("New Name");
            assertThat(user.getEmail()).isEqualTo("new@example.com");
            assertThat(user.getUpdatedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("reports nothing when the submitted values are unchanged")
        void reportsNothingForANoOp() {
            // A resubmitted form must not generate a notification, so "no change" has to
            // be distinguishable from "changed".
            User user = newUser();

            var changed = user.updateProfile("Someone Example", "someone@example.com", LATER);

            assertThat(changed).isEmpty();
            assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("treats a differently-cased email as unchanged")
        void ignoresCaseOnlyEmailChange() {
            User user = newUser();

            var changed = user.updateProfile(null, "SOMEONE@EXAMPLE.COM", LATER);

            assertThat(changed).isEmpty();
        }

        @Test
        @DisplayName("leaves null fields untouched, supporting partial updates")
        void leavesNullFieldsAlone() {
            User user = newUser();

            var changed = user.updateProfile("Only The Name", null, LATER);

            assertThat(changed).containsExactly("fullName");
            assertThat(user.getEmail()).isEqualTo("someone@example.com");
        }

        @Test
        @DisplayName("returns an unmodifiable list of changed fields")
        void changedFieldsAreUnmodifiable() {
            User user = newUser();
            var changed = user.updateProfile("Another Name", null, LATER);

            assertThat(changed.getClass().getName()).contains("Unmodifiable");
        }
    }

    @Nested
    @DisplayName("credentials and status")
    class CredentialsAndStatus {

        @Test
        @DisplayName("replaces the stored hash and stamps the change")
        void changesPassword() {
            User user = newUser();

            user.changePassword("{argon2}$new-hash", LATER);

            assertThat(user.getPasswordHash()).isEqualTo("{argon2}$new-hash");
            assertThat(user.getUpdatedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("disabling revokes the ability to authenticate")
        void disabling() {
            User user = newUser();

            user.disable(LATER);

            assertThat(user.isActive()).isFalse();
            assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);
        }

        @Test
        @DisplayName("granting a role is additive")
        void grantsRoles() {
            User user = newUser();

            user.grant(Role.ADMIN);

            assertThat(user.getRoles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
        }

        @Test
        @DisplayName("the exposed role set cannot be mutated from outside the entity")
        void roleSetIsUnmodifiable() {
            // Otherwise a caller could grant itself ADMIN by mutating the returned set.
            User user = newUser();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> user.getRoles().add(Role.ADMIN))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    @DisplayName("toString never exposes the password hash")
    void toStringExcludesPasswordHash() {
        // Entities end up in log lines and exception messages; a hash leaking there is
        // an offline-cracking head start.
        User user = newUser();

        assertThat(user.toString()).doesNotContain("$hash").contains("someone@example.com");
    }

    @Test
    @DisplayName("role authorities carry the ROLE_ prefix Spring Security expects")
    void roleAuthorityNaming() {
        assertThat(Role.ADMIN.authority()).isEqualTo("ROLE_ADMIN");
        assertThat(Role.USER.authority()).isEqualTo("ROLE_USER");
    }
}
