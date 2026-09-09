package com.trams.notification.web;

import com.trams.notification.domain.NotificationNotFoundException;
import com.trams.web.ProblemDetails;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** This service's domain-specific error mapping. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationExceptionHandler {

    @ExceptionHandler(NotificationNotFoundException.class)
    public ProblemDetail handleNotFound(NotificationNotFoundException e) {
        return ProblemDetails.of(
                HttpStatus.NOT_FOUND,
                "No such notification exists for this user.",
                NotificationNotFoundException.ERROR_CODE);
    }
}
