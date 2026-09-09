package com.trams.user.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing.
 *
 * <p><strong>Argon2id</strong> is the default. It is memory-hard, which is what
 * distinguishes it from bcrypt in practice: an attacker with GPUs or ASICs gains far less
 * advantage when each guess must also allocate tens of megabytes.
 *
 * <p>The encoder is wrapped in a {@link DelegatingPasswordEncoder}, so every stored hash
 * is prefixed with the algorithm that produced it ({@code {argon2}$argon2id$...}). This
 * is what makes future migration possible without a flag day: bcrypt hashes remain
 * verifiable, and new passwords are written with the current default. Storing a bare hash
 * with no algorithm marker is the mistake that makes an algorithm upgrade a breaking
 * change.
 */
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
