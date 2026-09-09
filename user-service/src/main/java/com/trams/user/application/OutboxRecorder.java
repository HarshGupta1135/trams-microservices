package com.trams.user.application;

import com.trams.contracts.EventEnvelope;
import com.trams.contracts.UserEventPayload;
import com.trams.contracts.UserEventType;
import com.trams.user.domain.OutboxEvent;
import com.trams.user.infrastructure.persistence.OutboxEventRepository;
import com.trams.web.CorrelationIdProvider;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends a domain event to the transactional outbox.
 *
 * <p>This is the write half of reliable publishing. It performs a plain database INSERT
 * and never touches the broker, which is precisely the point: the event becomes durable
 * as part of the same transaction as the domain change, so the two cannot diverge. A
 * broker outage cannot fail the business operation, and a rolled-back operation cannot
 * leak an event.
 *
 * <p>{@link Propagation#MANDATORY} enforces this by construction — calling it outside a
 * transaction is a startup-visible programming error rather than a subtle atomicity bug
 * discovered in production.
 */
@Service
public class OutboxRecorder {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecorder.class);

    private static final String AGGREGATE_TYPE = "User";

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final CorrelationIdProvider correlationIds;

    public OutboxRecorder(
            OutboxEventRepository outbox, ObjectMapper objectMapper, CorrelationIdProvider correlationIds) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.correlationIds = correlationIds;
    }

    /**
     * Records an event, to be published after the surrounding transaction commits.
     *
     * @throws IllegalStateException if invoked outside an active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope<UserEventPayload> record(UserEventPayload payload, Instant occurredAt) {
        UserEventType eventType = payload.eventType();
        String correlationId = correlationIds.current();

        EventEnvelope<UserEventPayload> envelope =
                EventEnvelope.of(eventType, payload, correlationId, occurredAt);

        // The complete envelope is stored, so the relay ships bytes without needing to
        // understand any event type.
        String serialisedEnvelope = objectMapper.writeValueAsString(envelope);

        OutboxEvent row =
                OutboxEvent.pending(
                        envelope.id(),
                        AGGREGATE_TYPE,
                        payload.userId(),
                        envelope.type(),
                        envelope.dataVersion(),
                        envelope.subject(),
                        serialisedEnvelope,
                        correlationId,
                        occurredAt);

        outbox.save(row);

        log.debug(
                "Recorded outbox event {} ({}) for user {}", envelope.id(), envelope.type(), payload.userId());

        return envelope;
    }
}
