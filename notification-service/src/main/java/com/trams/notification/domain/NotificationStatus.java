package com.trams.notification.domain;

public enum NotificationStatus {
    /** Recorded but not yet delivered. A redelivery of the event resumes from here. */
    PENDING,
    /** Delivered successfully. A redelivery of the event is acknowledged and skipped. */
    SENT,
    /** A delivery attempt failed transiently; the event will be redelivered. */
    FAILED,
    /**
     * Delivery attempts are exhausted. The row is retained as the record of a
     * notification the system could not deliver, and the message is dead-lettered so an
     * operator can replay it after fixing the cause.
     */
    DEAD
}
