package com.trams.web;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Supplies the current request's correlation id to code that needs to stamp it onto an event.
 */
@Component
public class CorrelationIdProvider {

    public String current() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId != null && !correlationId.isBlank() ? correlationId : UUID.randomUUID().toString();
    }
}
