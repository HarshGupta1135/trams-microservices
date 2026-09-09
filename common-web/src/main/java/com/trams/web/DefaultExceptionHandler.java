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

/** Framework-level error handling shared by every service. */
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

    /** Bean Validation failures on a request body, reported field by field. */
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

    /** Final step for every exception the base class handles. */
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

    /** Concurrent writes touched the same row. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleConcurrentModification(ObjectOptimisticLockingFailureException e) {
        return ProblemDetails.of(
                HttpStatus.CONFLICT,
                "The resource was modified concurrently. Please retry.",
                "CONCURRENT_MODIFICATION");
    }

    /** Anything genuinely unanticipated — a defect. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception while processing a request", e);

        return ProblemDetails.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Quote the correlation ID if the problem persists.",
                "INTERNAL_ERROR");
    }
}
