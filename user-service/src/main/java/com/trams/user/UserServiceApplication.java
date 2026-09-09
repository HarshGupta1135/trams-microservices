package com.trams.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Owns user identity and credentials, and is the only component holding the JWT signing
 * key.
 *
 * <p>Scheduling is enabled for the transactional outbox relay, which is what turns a
 * committed database change into a published domain event.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
