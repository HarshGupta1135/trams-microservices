package com.trams.user.config;

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

/** OpenAPI document for the User Service. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI userServiceOpenApi(@Value("${trams.docs.public-url:http://localhost:8080}") String publicUrl) {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("TRAMS User Service API")
                                .version("1.0.0")
                                .description(
                                        """
                                        Identity and access management: registration, authentication, token
                                        lifecycle and profile management.

                                        Every state change is published as a domain event through a
                                        transactional outbox to NATS JetStream, where the Notification
                                        Service consumes it. The two services never call each other over
                                        HTTP.

                                        **Authentication.** Obtain a token pair from `POST /api/v1/auth/login`
                                        and send the access token as `Authorization: Bearer <token>`. Access
                                        tokens are short-lived; use `POST /api/v1/auth/refresh` to rotate.
                                        Refresh tokens are single-use — replaying one revokes the whole
                                        token family.
                                        """)
                                .contact(new Contact().name("TRAMS"))
                                .license(new License().name("MIT")))
                .servers(List.of(new Server().url(publicUrl).description("API Gateway (the only public entry point)")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        BEARER_SCHEME,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "RS256-signed access token issued by this service.")));
    }
}
