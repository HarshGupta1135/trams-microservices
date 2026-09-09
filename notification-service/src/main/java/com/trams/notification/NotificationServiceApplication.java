package com.trams.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Consumes user domain events from JetStream and delivers notifications.
 *
 * <p>It has no HTTP client for the User Service by design: everything it needs arrives in
 * the event payload, which is what makes the two services independently deployable.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
