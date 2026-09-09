package com.trams.notification.infrastructure.delivery;

import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the notification to the application log instead of sending it.
 *
 * <p>Always available, which is what lets the whole event pipeline be demonstrated and
 * tested with no mail server: select it with {@code trams.notification.channel=LOG}.
 *
 * <p>Only the subject and recipient are logged, never the rendered body - notification
 * bodies contain personal data, and logs are typically retained longer and read more
 * widely than the notifications themselves.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.LOG;
    }

    @Override
    public void send(Notification notification) {
        log.info(
                "NOTIFICATION [{}] to {} <{}>: \"{}\" (event {}, notification {})",
                notification.getEventType(),
                notification.getRecipientName(),
                notification.getRecipientEmail(),
                notification.getSubject(),
                notification.getEventId(),
                notification.getId());
    }
}
