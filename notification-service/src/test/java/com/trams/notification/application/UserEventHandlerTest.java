package com.trams.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trams.contracts.EventEnvelope;
import com.trams.contracts.UserEventPayload;
import com.trams.contracts.UserEventType;
import com.trams.messaging.DurableEventConsumer.DeliveryContext;
import com.trams.messaging.EventCodec;
import com.trams.messaging.PermanentEventException;
import com.trams.messaging.TransientEventException;
import com.trams.notification.config.NotificationProperties;
import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationChannel;
import com.trams.notification.domain.NotificationContent;
import com.trams.notification.infrastructure.delivery.DeliveryException;
import com.trams.notification.infrastructure.delivery.DeliveryRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how the handler settles each kind of outcome.
 *
 * <p>The classification is the whole point: getting it wrong means either retrying a
 * message that can never succeed (blocking the queue behind a poison event) or discarding
 * one that would have succeeded on a second attempt (losing a user's notification).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserEventHandlerTest {

    private static final DeliveryContext FIRST_ATTEMPT = new DeliveryContext(1, 5, false);
    private static final DeliveryContext FINAL_ATTEMPT = new DeliveryContext(5, 5, true);

    @Mock private EventCodec codec;
    @Mock private NotificationComposer composer;
    @Mock private NotificationStore store;
    @Mock private DeliveryRouter router;

    private UserEventHandler handler;

    @BeforeEach
    void setUp() {
        NotificationProperties properties =
                new NotificationProperties(
                        NotificationChannel.EMAIL,
                        "TRAMS",
                        "no-reply@trams.local",
                        "TRAMS",
                        "http://localhost:8080",
                        new NotificationProperties.Consumer(
                                Duration.ofSeconds(60), 5, 256, 25, 4,
                                Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofMinutes(2)));

        handler = new UserEventHandler(codec, composer, store, router, properties, new SimpleMeterRegistry());
    }

    /**
     * The envelope rejects a null payload by contract, so an empty node stands in for the
     * raw JSON the codec would normally hand back.
     */
    private static EventEnvelope<JsonNode> envelopeOfType(String type) {
        JsonNode emptyPayload = new JsonMapper().createObjectNode();

        return new EventEnvelope<>(
                UUID.randomUUID(), 1, type, 1, "user-service",
                "user.events.registered", Instant.now(), "corr-1", emptyPayload);
    }

    private static UserEventPayload payload() {
        return new UserEventPayload.UserRegistered(
                UUID.randomUUID(), "someone@example.com", "Someone", Set.of("USER"), Instant.now());
    }

    private static Notification pendingNotification(EventEnvelope<JsonNode> envelope, UserEventPayload data) {
        return Notification.pending(
                envelope.id(), envelope.type(), data.userId(), data.email(), data.fullName(),
                NotificationChannel.EMAIL, "Welcome", "<p>Hi</p>", envelope.correlationId(), Instant.now());
    }

    @Test
    @DisplayName("an unknown event type is permanent, so it is dead-lettered rather than retried")
    void unknownEventTypeIsPermanent() throws Exception {
        // During a rolling deploy a newer producer may emit an event this consumer does
        // not know. Retrying it five times would achieve nothing; dead-lettering makes it
        // visible and replayable once this service is updated.
        EventEnvelope<JsonNode> envelope = envelopeOfType("user.invented_yesterday");

        assertThatThrownBy(() -> handler.handle(envelope, FIRST_ATTEMPT))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("user.invented_yesterday");

        verify(store, never()).claim(any(), any(), any(), any());
        verify(router, never()).deliver(any());
    }

    @Test
    @DisplayName("a duplicate delivery is acknowledged without sending anything again")
    void duplicateIsSkipped() throws Exception {
        // This is the observable half of idempotency: the store reports the event as
        // already delivered, and the handler must NOT send a second email.
        EventEnvelope<JsonNode> envelope = envelopeOfType(UserEventType.REGISTERED.wireName());
        UserEventPayload data = payload();

        when(codec.readPayload(eq(envelope), any())).thenReturn(data);
        when(composer.compose(data)).thenReturn(new NotificationContent("Welcome", "<p>Hi</p>"));
        when(store.claim(any(), any(), any(), any())).thenReturn(Optional.empty());

        handler.handle(envelope, FIRST_ATTEMPT);

        verify(router, never()).deliver(any());
        verify(store, never()).markSent(any());
    }

    @Test
    @DisplayName("a successful delivery is recorded as sent")
    void successfulDeliveryIsRecorded() throws Exception {
        EventEnvelope<JsonNode> envelope = envelopeOfType(UserEventType.REGISTERED.wireName());
        UserEventPayload data = payload();
        Notification notification = pendingNotification(envelope, data);

        when(codec.readPayload(eq(envelope), any())).thenReturn(data);
        when(composer.compose(data)).thenReturn(new NotificationContent("Welcome", "<p>Hi</p>"));
        when(store.claim(any(), any(), any(), any())).thenReturn(Optional.of(notification));

        handler.handle(envelope, FIRST_ATTEMPT);

        verify(router).deliver(notification);
        verify(store).markSent(notification.getId());
    }

    @Test
    @DisplayName("a failed delivery is transient, so the event is redelivered")
    void failedDeliveryIsTransient() throws Exception {
        // Mail failures are almost always temporary. Treating them as permanent would
        // silently drop a user's welcome email because a relay restarted.
        EventEnvelope<JsonNode> envelope = envelopeOfType(UserEventType.REGISTERED.wireName());
        UserEventPayload data = payload();
        Notification notification = pendingNotification(envelope, data);

        when(codec.readPayload(eq(envelope), any())).thenReturn(data);
        when(composer.compose(data)).thenReturn(new NotificationContent("Welcome", "<p>Hi</p>"));
        when(store.claim(any(), any(), any(), any())).thenReturn(Optional.of(notification));
        doThrow(new DeliveryException("relay refused")).when(router).deliver(notification);

        assertThatThrownBy(() -> handler.handle(envelope, FIRST_ATTEMPT))
                .isInstanceOf(TransientEventException.class);

        // Not the final attempt, so the row stays retryable rather than DEAD.
        verify(store).markFailed(eq(notification.getId()), anyString(), eq(false));
        verify(store, never()).markSent(any());
    }

    @Test
    @DisplayName("on the final attempt the notification is marked permanently undelivered")
    void finalAttemptMarksTheNotificationDead() throws Exception {
        // The message is about to be dead-lettered, so leaving the row looking merely
        // "failed, will retry" would misrepresent its state forever.
        EventEnvelope<JsonNode> envelope = envelopeOfType(UserEventType.REGISTERED.wireName());
        UserEventPayload data = payload();
        Notification notification = pendingNotification(envelope, data);

        when(codec.readPayload(eq(envelope), any())).thenReturn(data);
        when(composer.compose(data)).thenReturn(new NotificationContent("Welcome", "<p>Hi</p>"));
        when(store.claim(any(), any(), any(), any())).thenReturn(Optional.of(notification));
        doThrow(new DeliveryException("relay still refusing")).when(router).deliver(notification);

        assertThatThrownBy(() -> handler.handle(envelope, FINAL_ATTEMPT))
                .isInstanceOf(TransientEventException.class);

        verify(store).markFailed(eq(notification.getId()), anyString(), eq(true));
    }

    @Test
    @DisplayName("a payload that violates the contract propagates as permanent")
    void contractViolationIsPermanent() throws Exception {
        // The codec enforces the shared contract; a producer bug must not be retried into
        // oblivion, it must be surfaced.
        EventEnvelope<JsonNode> envelope = envelopeOfType(UserEventType.REGISTERED.wireName());

        when(codec.readPayload(eq(envelope), any()))
                .thenThrow(new PermanentEventException("email must not be blank", "contract-violation"));

        assertThatThrownBy(() -> handler.handle(envelope, FIRST_ATTEMPT))
                .isInstanceOf(PermanentEventException.class);

        verify(store, never()).claim(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the notification is composed before it is claimed, so nothing is stored unrenderable")
    void composesBeforeClaiming() {
        EventEnvelope<JsonNode> envelope = envelopeOfType(UserEventType.REGISTERED.wireName());
        UserEventPayload data = payload();

        when(codec.readPayload(eq(envelope), any())).thenReturn(data);
        when(composer.compose(data)).thenReturn(new NotificationContent("Welcome", "<p>Hi</p>"));
        when(store.claim(any(), any(), any(), eq(NotificationChannel.EMAIL))).thenReturn(Optional.empty());

        handler.handle(envelope, FIRST_ATTEMPT);

        // The configured channel is what gets recorded on the row.
        verify(store).claim(eq(envelope), eq(data), any(NotificationContent.class), eq(NotificationChannel.EMAIL));
        assertThat(data.eventType()).isEqualTo(UserEventType.REGISTERED);
    }
}
