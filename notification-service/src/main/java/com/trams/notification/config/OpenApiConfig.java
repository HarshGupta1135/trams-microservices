package com.trams.notification.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document for the Notification Service. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI notificationServiceOpenApi(
            @Value("${trams.docs.public-url:http://localhost:8080}") String publicUrl) {

        return new OpenAPI()
                .info(
                        new Info()
                                .title("TRAMS Notification Service API")
                                .version("1.0.0")
                                .description(
                                        """
                                        Notification history for the authenticated user.

                                        This API is for clients only. The service receives its work from
                                        NATS JetStream, by consuming the user domain events published by
                                        the User Service - there is no HTTP endpoint here that the User
                                        Service calls, and no HTTP client pointing back at it.

                                        Event handling is idempotent: a redelivered event cannot produce a
                                        second notification, because the originating event id is unique in
                                        the notifications table.
                                        """)
                                .contact(new Contact().name("TRAMS"))
                                .license(new License().name("MIT")))
                .servers(
                        List.of(
                                new Server()
                                        .url(publicUrl)
                                        .description("API Gateway (the only public entry point)")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        BEARER_SCHEME,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "RS256 access token issued by the User Service.")));
    }
}
