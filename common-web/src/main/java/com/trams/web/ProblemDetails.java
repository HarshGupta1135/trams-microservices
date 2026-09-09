package com.trams.web;

import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 error bodies with the two extension members used across this system: a stable
 * machine-readable code, and the correlationId that.
 */
public final class ProblemDetails {

    private static final String DOC_BASE = "https://docs.trams.local/errors/";

    private ProblemDetails() {}

    public static ProblemDetail of(HttpStatus status, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);

        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create(DOC_BASE + slug(code)));
        problem.setProperty("code", code);

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            problem.setProperty("correlationId", correlationId);
        }

        return problem;
    }

    /**
     * Adds the correlation id to a ProblemDetail that was built elsewhere - for instance one
     * Spring MVC produced for a framework-level failure - so every.
     */
    public static void attachCorrelationId(ProblemDetail problem) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);

        if (correlationId != null && !correlationId.isBlank()) {
            problem.setProperty("correlationId", correlationId);
        }
    }

    /**
     * Pre-rendered JSON, for filters that run before Spring MVC's message converters and
     * therefore cannot return a ProblemDetail object.
     */
    public static String asJson(HttpStatus status, String detail, String code) {
        return "{\"type\":\"%s%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\"}"
                .formatted(DOC_BASE, slug(code), status.getReasonPhrase(), status.value(), detail, code);
    }

    private static String slug(String code) {
        return code.toLowerCase().replace('_', '-');
    }
}
