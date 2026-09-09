package com.trams.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trams.contracts.UserEventPayload;
import com.trams.notification.config.NotificationProperties;
import com.trams.notification.domain.NotificationChannel;
import com.trams.notification.domain.NotificationContent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders every notification against the real templates.
 *
 * <p>Worth testing directly because a template failure is otherwise invisible until an
 * event arrives in production: the composer runs on the consumer thread, so a broken
 * template would surface as an unprocessable event rather than as a failing build.
 */
class NotificationComposerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant WHEN = Instant.parse("2026-02-03T14:30:00Z");

    private static NotificationComposer composer;

    @BeforeAll
    static void setUp() {
        // Mirrors Spring Boot's Thymeleaf auto-configuration exactly, including the
        // engine type. A plain TemplateEngine would use the OGNL-based standard dialect,
        // which Boot deliberately excludes in favour of SpEL - so testing against one
        // would both fail at runtime and test a dialect the application never uses.
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        NotificationProperties properties =
                new NotificationProperties(
                        NotificationChannel.EMAIL,
                        "TRAMS",
                        "no-reply@trams.local",
                        "TRAMS",
                        "https://app.trams.local",
                        new NotificationProperties.Consumer(
                                Duration.ofSeconds(60), 5, 256, 25, 4,
                                Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofMinutes(2)));

        composer = new NotificationComposer(engine, properties);
    }

    @Test
    @DisplayName("the welcome message names the recipient and the application")
    void composesWelcome() {
        NotificationContent content =
                composer.compose(
                        new UserEventPayload.UserRegistered(
                                USER_ID, "someone@example.com", "Ada Lovelace", Set.of("USER"), WHEN));

        assertThat(content.subject()).isEqualTo("Welcome to TRAMS");
        assertThat(content.body())
                .contains("Ada Lovelace")
                .contains("someone@example.com")
                .contains("3 February 2026")
                // The call to action points at the configured application URL.
                .contains("https://app.trams.local");
    }

    @Test
    @DisplayName("the profile message lists the changed fields in readable prose")
    void composesProfileUpdate() {
        NotificationContent content =
                composer.compose(
                        new UserEventPayload.UserProfileUpdated(
                                USER_ID, "someone@example.com", "Ada Lovelace",
                                List.of("fullName", "email"), WHEN));

        assertThat(content.subject()).isEqualTo("Your TRAMS profile was updated");
        // "fullName" would read as an internal identifier; the message says "full name".
        assertThat(content.body()).contains("full name").contains("email");
    }

    @Test
    @DisplayName("the password alert includes the originating address")
    void composesPasswordChange() {
        NotificationContent content =
                composer.compose(
                        new UserEventPayload.UserPasswordChanged(
                                USER_ID, "someone@example.com", "Ada Lovelace", "203.0.113.7", WHEN));

        assertThat(content.subject()).isEqualTo("Your TRAMS password was changed");
        assertThat(content.body())
                .contains("203.0.113.7")
                // A recipient who did not make the change needs to be told what to do.
                .contains("If this was not you");
    }

    @Test
    @DisplayName("a missing IP degrades to readable text rather than printing null")
    void composesPasswordChangeWithoutIp() {
        NotificationContent content =
                composer.compose(
                        new UserEventPayload.UserPasswordChanged(
                                USER_ID, "someone@example.com", "Ada Lovelace", null, WHEN));

        assertThat(content.body()).contains("an unknown address").doesNotContain("null");
    }

    @Test
    @DisplayName("the deletion message confirms the account is gone")
    void composesDeletion() {
        NotificationContent content =
                composer.compose(
                        new UserEventPayload.UserDeleted(USER_ID, "someone@example.com", "Ada Lovelace", WHEN));

        assertThat(content.subject()).isEqualTo("Your TRAMS account has been deleted");
        assertThat(content.body()).contains("Ada Lovelace").contains("deleted");
    }

    @Test
    @DisplayName("a name containing markup is escaped, not injected into the email")
    void escapesUntrustedInput() {
        // The name comes from user input that crossed a service boundary. Thymeleaf
        // escapes interpolated values by default; this asserts that nobody has reached
        // for th:utext, which would turn a display name into an HTML injection vector.
        NotificationContent content =
                composer.compose(
                        new UserEventPayload.UserRegistered(
                                USER_ID,
                                "someone@example.com",
                                "<script>alert('xss')</script>",
                                Set.of("USER"),
                                WHEN));

        assertThat(content.body()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("every message is a complete HTML document with the shared shell")
    void producesCompleteDocuments() {
        List<UserEventPayload> all =
                List.of(
                        new UserEventPayload.UserRegistered(
                                USER_ID, "a@example.com", "A", Set.of("USER"), WHEN),
                        new UserEventPayload.UserProfileUpdated(
                                USER_ID, "a@example.com", "A", List.of("fullName"), WHEN),
                        new UserEventPayload.UserPasswordChanged(USER_ID, "a@example.com", "A", "203.0.113.7", WHEN),
                        new UserEventPayload.UserDeleted(USER_ID, "a@example.com", "A", WHEN));

        for (UserEventPayload payload : all) {
            NotificationContent content = composer.compose(payload);

            assertThat(content.subject()).as("subject for %s", payload.eventType()).isNotBlank();
            assertThat(content.body())
                    .as("body for %s", payload.eventType())
                    .contains("<!DOCTYPE html>")
                    // The shared shell rendered, rather than the fragment failing silently.
                    .contains("This is an automated message")
                    // No unresolved Thymeleaf expressions left in the output.
                    .doesNotContain("th:text")
                    .doesNotContain("${");
        }
    }
}
