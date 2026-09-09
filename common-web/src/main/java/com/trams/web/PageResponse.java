package com.trams.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Envelope for a paged collection.
 *
 * <p>Spring's own {@code Page} serialises with an unstable, implementation-defined shape,
 * so a fixed contract is defined here instead. Collection endpoints are always paged: an
 * unbounded list endpoint is a denial-of-service vector against the service's own memory
 * once the table grows.
 */
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages, boolean hasNext) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
