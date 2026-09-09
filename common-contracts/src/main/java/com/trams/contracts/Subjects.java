package com.trams.contracts;

/** Central registry of broker topology names. */
public final class Subjects {

    /** JetStream stream that durably persists all user domain events. */
    public static final String STREAM_USER_EVENTS = "USER_EVENTS";

    /** JetStream stream holding messages that could not be processed. */
    public static final String STREAM_DEAD_LETTER = "DEAD_LETTER";

    public static final String USER_EVENTS_PREFIX = "user.events";
    public static final String USER_EVENTS_WILDCARD = USER_EVENTS_PREFIX + ".>";

    public static final String DEAD_LETTER_PREFIX = "dlq";
    public static final String DEAD_LETTER_WILDCARD = DEAD_LETTER_PREFIX + ".>";

    /** Durable consumer shared by every Notification Service replica. */
    public static final String NOTIFICATION_CONSUMER = "notification-worker";

    private Subjects() {}

    public static String userEvent(String fact) {
        return USER_EVENTS_PREFIX + "." + fact;
    }

    public static String deadLetter(String consumerName) {
        return DEAD_LETTER_PREFIX + "." + consumerName;
    }
}
