package com.trams.web;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Supplies the current request's correlation id to code that needs to stamp it onto an
 * event.
 *
 * <p>Reading it from the MDC keeps the id out of every service method signature. The
 * fallback covers work with no inbound request - a scheduled job, for instance - where a
 * fresh id is still better than none.
 */
@Component
public class CorrelationIdProvider {

    public String current() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return correlationId != null && !correlationId.isBlank() ? correlationId : UUID.randomUUID().toString();
    }
}
