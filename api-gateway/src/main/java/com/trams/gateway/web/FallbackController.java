package com.trams.gateway.web;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responses served when a route's circuit breaker is open or its upstream timed out.
 *
 * <p>Without a fallback, a failing upstream surfaces as a bare 500 with no explanation. A
 * 503 plus {@code Retry-After} tells a client something specific and actionable: the
 * request was not processed, this is temporary, and here is when to try again. That
 * distinction matters because a client must not retry a non-idempotent request that may
 * have succeeded - and an open circuit guarantees it did not.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    private static final int RETRY_AFTER_SECONDS = 5;

    @RequestMapping("/{service}")
    public ResponseEntity<ProblemDetail> fallback(@PathVariable String service) {
        log.warn("Serving fallback for '{}': the upstream is unavailable or the circuit is open", service);

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The %s service is temporarily unavailable. The request was not processed; please retry."
                                .formatted(service));

        problem.setTitle("Service Unavailable");
        problem.setType(URI.create("https://docs.trams.local/errors/service-unavailable"));
        problem.setProperty("code", "UPSTREAM_UNAVAILABLE");
        problem.setProperty("service", service);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(RETRY_AFTER_SECONDS))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
