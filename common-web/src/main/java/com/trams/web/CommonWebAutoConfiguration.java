package com.trams.web;

import com.trams.security.TokenProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Wires the shared HTTP infrastructure into any service that declares this module.
 *
 * <p>Delivered as an auto-configuration so a service inherits correlation propagation,
 * gateway-only admission control and correct token verification by adding a dependency,
 * rather than by remembering to import three classes.
 */
@AutoConfiguration
@EnableConfigurationProperties({TokenProperties.class, InternalAuthProperties.class})
@Import({
    CorrelationIdFilter.class,
    CorrelationIdProvider.class,
    InternalKeyFilter.class,
    JwtVerificationConfig.class,
    DefaultExceptionHandler.class
})
public class CommonWebAutoConfiguration {}
