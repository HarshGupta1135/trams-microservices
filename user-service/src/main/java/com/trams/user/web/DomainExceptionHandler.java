package com.trams.user.web;

import com.trams.user.domain.AccountDisabledException;
import com.trams.user.domain.DomainException;
import com.trams.user.domain.EmailAlreadyRegisteredException;
import com.trams.user.domain.InvalidCredentialsException;
import com.trams.user.domain.InvalidRefreshTokenException;
import com.trams.user.domain.UserNotFoundException;
import com.trams.web.ProblemDetails;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates this service's domain failures into HTTP responses.
 *
 * <p>Ordered ahead of {@code common-web}'s {@code DefaultExceptionHandler}, which handles
 * everything framework-level (validation, malformed bodies, access denied, the catch-all).
 * Only what is specific to user identity lives here.
 *
 * <p>Mapping status codes in one table rather than scattering them across controllers
 * means the API's error contract can be read in one place — and that a new domain
 * exception cannot accidentally surface as a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DomainExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DomainExceptionHandler.class);

    private static final Map<Class<? extends DomainException>, HttpStatus> STATUSES =
            Map.of(
                    EmailAlreadyRegisteredException.class, HttpStatus.CONFLICT,
                    UserNotFoundException.class, HttpStatus.NOT_FOUND,
                    InvalidCredentialsException.class, HttpStatus.UNAUTHORIZED,
                    InvalidRefreshTokenException.class, HttpStatus.UNAUTHORIZED,
                    AccountDisabledException.class, HttpStatus.FORBIDDEN);

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException e) {
        HttpStatus status = STATUSES.getOrDefault(e.getClass(), HttpStatus.BAD_REQUEST);

        // Expected business outcomes, not defects: logged at INFO with no stack trace, so
        // they neither drown out real problems nor trigger alerts.
        log.info("Request rejected ({}): {}", e.errorCode(), e.getMessage());

        return ProblemDetails.of(status, e.getMessage(), e.errorCode());
    }
}
