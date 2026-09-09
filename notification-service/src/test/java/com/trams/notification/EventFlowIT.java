package com.trams.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.trams.contracts.EventEnvelope;
import com.trams.contracts.Subjects;
import com.trams.contracts.UserEventPayload;
import com.trams.contracts.UserEventType;
import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationStatus;
import com.trams.notification.infrastructure.persistence.NotificationRepository;
import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PublishOptions;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.impl.Headers;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** End-to-end test of the event pipeline against a real PostgreSQL and a real NATS broker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventFlowIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"))
                    .withDatabaseName("notifications_test")
                    .withUsername("notifications_test")
                    .withPassword("notifications_test");

    /** There is no Testcontainers module for NATS, so the official image is driven directly. */
    private static final GenericContainer<?> NATS =
            new GenericContainer<>(DockerImageName.parse("nats:2.11-alpine"))
                    .withCommand("-js")
                    .withExposedPorts(4222)
                    .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    private static String publicKeyBase64;

    /* Containers are started in a static initialiser rather than through the */
    static {
        POSTGRES.start();
        NATS.start();
        publicKeyBase64 = generateVerificationKey();
        createUserEventsStream();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("trams.nats.url", EventFlowIT::natsUrl);
        registry.add("trams.nats.tls.enabled", () -> false);
        registry.add("trams.nats.username", () -> "test");
        registry.add("trams.nats.password", () -> "test");

        // The LOG channel keeps the test free of a mail server; delivery semantics are
        // identical, only the transport differs.
        registry.add("trams.notification.channel", () -> "LOG");
        registry.add("trams.notification.from-address", () -> "no-reply@trams.test");

        registry.add("trams.security.token.public-key-base64", () -> publicKeyBase64);
        registry.add("trams.security.token.issuer", () -> "https://trams.test");
        registry.add("trams.security.token.audience", () -> "trams-api");
        registry.add("trams.security.internal.api-key", () -> "a-test-internal-key-of-sufficient-length");

        // Faster feedback when asserting redelivery behaviour.
        registry.add("trams.notification.consumer.ack-wait", () -> "5s");
        registry.add("trams.notification.consumer.backoff-base", () -> "500ms");
    }

    @Autowired private NotificationRepository notifications;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("an event published to the broker becomes a delivered notification")
    void consumesAnEventAndDeliversANotification() throws Exception {
        EventEnvelope<UserEventPayload> event = registeredEvent("first@example.com");

        publish(event);

        Notification notification = awaitNotificationFor(event.id());

        assertThat(notification.getRecipientEmail()).isEqualTo("first@example.com");
        assertThat(notification.getEventType()).isEqualTo(UserEventType.REGISTERED.wireName());
        assertThat(notification.getSubject()).contains("Welcome");
        assertThat(notification.getCorrelationId()).isEqualTo(event.correlationId());

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(reload(notification.getId()).getStatus())
                                        .isEqualTo(NotificationStatus.SENT));
    }

    @Test
    @DisplayName("redelivering the same event produces exactly one notification")
    void isDuplicateSafe() throws Exception {
        // The core reliability property.
        EventEnvelope<UserEventPayload> event = registeredEvent("duplicate@example.com");

        publish(event);
        Notification first = awaitNotificationFor(event.id());

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(reload(first.getId()).getStatus())
                                        .isEqualTo(NotificationStatus.SENT));

        // Publish the identical envelope again, bypassing broker deduplication by using a fresh
        // message id.
        publishBypassingBrokerDeduplication(event);

        // Give the consumer time to handle (and skip) the redelivery.
        Thread.sleep(3_000);

        long count =
                notifications.findAll().stream()
                        .filter(n -> n.getEventId().equals(event.id()))
                        .count();

        assertThat(count).as("exactly one notification per event id").isEqualTo(1);
        assertThat(reload(first.getId()).getAttempts())
                .as("the duplicate must not trigger another delivery attempt")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an event whose type this consumer does not know is dead-lettered, not retried")
    void deadLettersUnknownEventTypes() throws Exception {
        // A newer producer during a rolling deploy. Retrying would be pointless; the
        // message must be preserved somewhere an operator can find and replay it.
        String unknownType = "user.invented_by_a_newer_producer";
        UUID eventId = UUID.randomUUID();

        String body =
                """
                {"id":"%s","specVersion":1,"type":"%s","dataVersion":1,"source":"user-service",\
                "subject":"user.events.registered","occurredAt":"%s","correlationId":"it-unknown",\
                "data":{"userId":"%s","email":"x@example.com","fullName":"X"}}"""
                        .formatted(eventId, unknownType, Instant.now(), UUID.randomUUID());

        try (Connection connection = connect()) {
            connection
                    .jetStream()
                    .publish(
                            Subjects.userEvent("registered"),
                            new Headers(),
                            body.getBytes(StandardCharsets.UTF_8),
                            PublishOptions.builder().messageId(eventId.toString()).build());
        }

        // It must land in the dead-letter stream, and must NOT create a notification.
        await().atMost(Duration.ofSeconds(25))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(deadLetterCount()).isPositive());

        assertThat(notifications.findByEventId(eventId)).isEmpty();
    }

    // Helpers

    private static String natsUrl() {
        return "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    }

    private static Connection connect() throws Exception {
        return Nats.connect(Options.builder().server(natsUrl()).build());
    }

    private EventEnvelope<UserEventPayload> registeredEvent(String email) {
        UserEventPayload payload =
                new UserEventPayload.UserRegistered(
                        UUID.randomUUID(), email, "Test Recipient", Set.of("USER"), Instant.now());

        return EventEnvelope.of(UserEventType.REGISTERED, payload, "it-" + UUID.randomUUID(), Instant.now());
    }

    private void publish(EventEnvelope<UserEventPayload> event) throws Exception {
        try (Connection connection = connect()) {
            connection
                    .jetStream()
                    .publish(
                            event.subject(),
                            new Headers(),
                            objectMapper.writeValueAsBytes(event),
                            PublishOptions.builder().messageId(event.id().toString()).build());
        }
    }

    /** Publishes the same envelope with a different broker message id. */
    private void publishBypassingBrokerDeduplication(EventEnvelope<UserEventPayload> event) throws Exception {
        try (Connection connection = connect()) {
            connection
                    .jetStream()
                    .publish(
                            event.subject(),
                            new Headers(),
                            objectMapper.writeValueAsBytes(event),
                            PublishOptions.builder().messageId(UUID.randomUUID().toString()).build());
        }
    }

    private Notification awaitNotificationFor(UUID eventId) {
        return await().atMost(Duration.ofSeconds(25))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> notifications.findByEventId(eventId), Optional::isPresent)
                .orElseThrow();
    }

    private Notification reload(UUID notificationId) {
        return notifications.findById(notificationId).orElseThrow();
    }

    private static long deadLetterCount() throws Exception {
        try (Connection connection = connect()) {
            JetStreamManagement management = connection.jetStreamManagement();
            return management.getStreamInfo(Subjects.STREAM_DEAD_LETTER).getStreamState().getMsgCount();
        } catch (Exception e) {
            // The stream is created by the service at startup; absence just means "not yet".
            return 0L;
        }
    }

    /**
     * The service requires a verification key at startup even though this test never presents a
     * token, so a throwaway key pair is generated rather than committing.
     */
    private static String generateVerificationKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            String pem =
                    "-----BEGIN PUBLIC KEY-----\n"
                            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                                    .encodeToString(keyPair.getPublic().getEncoded())
                            + "\n-----END PUBLIC KEY-----\n";

            return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a test verification key", e);
        }
    }

    /** Creates the stream the User Service would own in a real deployment. */
    private static void createUserEventsStream() {
        try (Connection connection = connect()) {
            connection
                    .jetStreamManagement()
                    .addStream(
                            StreamConfiguration.builder()
                                    .name(Subjects.STREAM_USER_EVENTS)
                                    .subjects(Subjects.USER_EVENTS_WILDCARD)
                                    .retentionPolicy(RetentionPolicy.Limits)
                                    .storageType(StorageType.File)
                                    .duplicateWindow(Duration.ofMinutes(5))
                                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Could not create the test event stream", e);
        }
    }
}
