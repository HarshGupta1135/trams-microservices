package com.trams.notification.infrastructure.delivery;

/** A delivery attempt failed. */
public class DeliveryException extends Exception {

    public DeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeliveryException(String message) {
        super(message);
    }
}
