package com.trams.notification.application;

import com.trams.contracts.EventEnvelope;
import com.trams.contracts.UserEventPayload;
import com.trams.contracts.UserEventType;
import com.trams.messaging.DurableEventConsumer.DeliveryContext;
import com.trams.messaging.EventCodec;
import com.trams.messaging.PermanentEventException;
import com.trams.messaging.TransientEventException;
import com.trams.notification.config.NotificationProperties;
import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationContent;
import com.trams.notification.infrastructure.delivery.DeliveryException;
import com.trams.notification.infrastructure.delivery.DeliveryRouter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Turns a user domain event into a delivered notification. */
@Service
public class UserEventHandler {

    private static final Logger log = LoggerFactory.getLogger(UserEventHandler.class);

    private final EventCodec codec;
    private final NotificationComposer composer;
    private final NotificationStore store;
    private final DeliveryRouter router;
    private final NotificationProperties properties;
    private final Counter delivered;
    private final Counter duplicates;
    private final Counter failures;

    public UserEventHandler(
            EventCodec codec,
            NotificationComposer composer,
            NotificationStore store,
            DeliveryRouter router,
            NotificationProperties properties,
            MeterRegistry meters) {

        this.codec = codec;
        this.composer = composer;
        this.store = store;
        this.router = router;
        this.properties = properties;

        this.delivered =
                Counter.builder("trams.notifications.delivered")
                        .description("Notifications delivered successfully")
                        .register(meters);
        // A healthy system produces some of these: they are evidence the idempotency
        // guard is doing its job, not a problem in themselves.
        this.duplicates =
                Counter.builder("trams.notifications.duplicates.skipped")
                        .description("Redelivered events whose notification had already been sent")
                        .register(meters);
        this.failures =
                Counter.builder("trams.notifications.delivery.failures")
                        .description("Failed delivery attempts")
                        .register(meters);
    }

    public void handle(EventEnvelope<JsonNode> envelope, DeliveryContext context) {
        UserEventType eventType =
                UserEventType.fromWireName(envelope.type())
                        .orElseThrow(
                                () ->
                                        new PermanentEventException(
                                                "No handler is registered for event type '%s'"
                                                        .formatted(envelope.type()),
                                                "unknown-event-type"));

        // Binds and validates against the shared contract.
        UserEventPayload payload = codec.readPayload(envelope, eventType.payloadType());

        NotificationContent content = composer.compose(payload);

        Optional<Notification> claimed =
                store.claim(envelope, payload, content, properties.channel());

        if (claimed.isEmpty()) {
            // Already delivered. Returning normally acknowledges the message.
            duplicates.increment();
            return;
        }

        Notification notification = claimed.get();

        try {
            router.deliver(notification);
            store.markSent(notification.getId());
            delivered.increment();

        } catch (DeliveryException e) {
            failures.increment();

            // On the final attempt the message is about to be dead-lettered, so the row
            // is marked permanently undelivered rather than left looking retryable.
            store.markFailed(notification.getId(), e.getMessage(), context.lastAttempt());

            log.warn(
                    "Delivery of notification {} failed on attempt {}/{}",
                    notification.getId(),
                    context.attempt(),
                    context.maxDeliver());

            throw new TransientEventException(
                    "Could not deliver notification " + notification.getId(), e);
        }
    }
}
