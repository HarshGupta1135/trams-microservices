package com.trams.notification.infrastructure.delivery;

import com.trams.notification.config.NotificationProperties;
import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationChannel;
import jakarta.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Routes a notification to the sender for its channel. */
@Service
public class DeliveryRouter {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRouter.class);

    private final Map<NotificationChannel, NotificationSender> senders =
            new EnumMap<>(NotificationChannel.class);

    private final NotificationProperties properties;

    public DeliveryRouter(List<NotificationSender> availableSenders, NotificationProperties properties) {
        this.properties = properties;

        for (NotificationSender sender : availableSenders) {
            senders.put(sender.channel(), sender);
        }

        log.info("Notification channels registered: {}", senders.keySet());
    }

    /** Verifies at startup that the configured channel can actually deliver. */
    @PostConstruct
    void verifyConfiguredChannel() {
        NotificationChannel configured = properties.channel();
        NotificationSender sender = senders.get(configured);

        if (sender == null) {
            throw new IllegalStateException(
                    "trams.notification.channel is %s but no sender is registered for it (registered: %s)."
                            .formatted(configured, senders.keySet()));
        }

        if (!sender.isAvailable()) {
            throw new IllegalStateException(
                    ("trams.notification.channel is %s but that channel is not usable with the current "
                            + "configuration. For EMAIL, set spring.mail.host; otherwise set "
                            + "trams.notification.channel=LOG.")
                            .formatted(configured));
        }

        log.info("Notification channel {} is configured and available", configured);
    }

    public void deliver(Notification notification) throws DeliveryException {
        NotificationSender sender = senders.get(notification.getChannel());

        if (sender == null) {
            throw new DeliveryException(
                    "No sender is registered for channel %s (available: %s)"
                            .formatted(notification.getChannel(), senders.keySet()));
        }

        sender.send(notification);
    }
}
