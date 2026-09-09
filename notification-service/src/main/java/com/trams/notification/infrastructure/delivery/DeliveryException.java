package com.trams.notification.infrastructure.delivery;

/**
 * A delivery attempt failed.
 *
 * <p>Treated as transient by the caller. Mail delivery failures are overwhelmingly
 * temporary - a relay restarting, a rate limit, a network blip - so the default is to
 * retry rather than discard someone's welcome email.
 */
public class DeliveryException extends Exception {

    public DeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeliveryException(String message) {
        super(message);
    }
}
