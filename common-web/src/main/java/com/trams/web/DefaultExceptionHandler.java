package com.trams.web;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Framework-level error handling shared by every service.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} rather than declaring a bare
 * {@code @ExceptionHandler(Exception.class)}. That base class already maps every standard
 * Spring MVC failure to the right status — a non-numeric path variable, an unparseable
 * enum in a query string, an unsupported {@code Content-Type}, a wrong HTTP method — and a
 * catch-all handler would intercept all of them first and report each as a 500.
 *
 * <p>That distinction is not cosmetic. A 500 means "this service is broken" and should wake
 * somebody; a 400 means "the caller sent something wrong" and should not. Misreporting one
 * as the other corrupts alerting, error budgets and client retry behaviour alike.
 *
 * <p>Ordered at the lowest precedence, so a service can register its own advice for domain
 * failures and have it consulted first.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class DefaultExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultExceptionHandler.class);

    /** Stable codes for the statuses Spring MVC raises on its own. */
    private static final Map<Integer, String> CODES_BY_STATUS =
            Map.of(
                    400, "BAD_REQUEST",
                    404, "NOT_FOUND",
                    405, "METHOD_NOT_ALLOWED",
                    406, "NOT_ACCEPTABLE",
                    413, "PAYLOAD_TOO_LARGE",
                    415, "UNSUPPORTED_MEDIA_TYPE",
                    429, "RATE_LIMITED",
                    503, "SERVICE_UNAVAILABLE");

    /**
     * Bean Validation failures on a request body, reported field by field.
     *
     * <p>Overrides the base implementation to add the {@code errors} array — a client
     * fixing a form needs to know <em>which</em> fields were rejected and why.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        List<Map<String, String>> errors =
                e.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        Map.of(
                                                "field",
                                                error.getField(),
                                                "message",
                                                error.getDefaultMessage() == null
                                                        ? "is invalid"
                                                        : error.getDefaultMessage()))
                        .toList();

        ProblemDetail problem =
                ProblemDetails.of(
                        HttpStatus.BAD_REQUEST, "The request payload is invalid.", "VALIDATION_ERROR");
        problem.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Final step for every exception the base class handles.
     *
     * <p>Spring already produced a {@link ProblemDetail}; this decorates it with the two
     * extension members the rest of the system uses, so a 415 from the framework looks
     * exactly like a 409 from the domain.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, status, request);

        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty(
                    "code", CODES_BY_STATUS.getOrDefault(status.value(), "REQUEST_NOT_ACCEPTABLE"));
            ProblemDetails.attachCorrelationId(problem);

            // Framework detail text can echo request content or class names back to the
            // caller; the specifics stay in the log.
            log.debug("Request rejected by the framework ({}): {}", status.value(), e.getMessage());
        }

        return response;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e) {
        return ProblemDetails.of(
                HttpStatus.FORBIDDEN, "You do not have permission to perform this action.", "FORBIDDEN");
    }

    /**
     * Concurrent writes touched the same row.
     *
     * <p>409, not 500: the request was well-formed and retrying it will usually succeed.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleConcurrentModification(ObjectOptimisticLockingFailureException e) {
        return ProblemDetails.of(
                HttpStatus.CONFLICT,
                "The resource was modified concurrently. Please retry.",
                "CONCURRENT_MODIFICATION");
    }

    /**
     * Anything genuinely unanticipated — a defect.
     *
     * <p>Reaches here only if no more specific handler matched, so a 500 now means what it
     * should. The stack trace is logged and a deliberately vague message returned:
     * exception text routinely contains SQL fragments, class names and filesystem paths.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception while processing a request", e);

        return ProblemDetails.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Quote the correlation ID if the problem persists.",
                "INTERNAL_ERROR");
    }
}
