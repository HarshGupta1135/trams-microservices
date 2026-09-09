package com.trams.contracts;

/**
 * NATS message headers accompanying every event.
 *
 * <p>Carrying identifiers in headers as well as in the envelope body lets infrastructure
 * (the dead-letter tooling, a broker CLI, a future router) inspect and route a message
 * without deserialising the payload.
 */
public final class EventHeaders {

    public static final String EVENT_ID = "X-Event-Id";
    public static final String EVENT_TYPE = "X-Event-Type";
    public static final String DATA_VERSION = "X-Data-Version";
    public static final String SOURCE = "X-Source";
    public static final String CORRELATION_ID = "X-Correlation-Id";

    /** Set only on messages copied into the dead-letter stream. */
    public static final String DLQ_REASON = "X-Dead-Letter-Reason";
    public static final String DLQ_ORIGIN_SUBJECT = "X-Dead-Letter-Origin-Subject";
    public static final String DLQ_CONSUMER = "X-Dead-Letter-Consumer";
    public static final String DLQ_DELIVERY_COUNT = "X-Delivery-Count";
    public static final String DLQ_FAILED_AT = "X-Dead-Letter-Failed-At";

    private EventHeaders() {}
}
