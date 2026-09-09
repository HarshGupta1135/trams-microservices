package com.trams.user.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Password hashing. */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

    private static final String DEFAULT_ENCODER_ID = "argon2";

    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders =
                Map.of(
                        // OWASP-aligned parameters: 16-byte salt, 32-byte hash,
                        // 1 lane, 16 MiB, 3 iterations.
                        DEFAULT_ENCODER_ID, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                        // Retained only so pre-existing bcrypt hashes stay verifiable.
                        "bcrypt", new BCryptPasswordEncoder());

        return new DelegatingPasswordEncoder(DEFAULT_ENCODER_ID, encoders);
    }
}
