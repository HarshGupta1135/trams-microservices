package com.trams.user.infrastructure.persistence;

import com.trams.user.domain.OutboxEvent;
import com.trams.user.domain.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of due events for this relay instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes the relay horizontally scalable.
     * Rows claimed by one instance are locked for the duration of its transaction, and
     * other instances step over them instead of blocking. Several replicas therefore
     * process disjoint batches concurrently with no coordination, no leader election and
     * no risk of two relays publishing the same row.
     *
     * <p>Ordering by {@code created_at} preserves per-aggregate causal order in the common
     * case. Note that strict global ordering is not guaranteed under concurrency — a
     * property inherent to parallel relays, and the reason consumers are written to be
     * idempotent rather than order-dependent.
     */
    @Query(
            value =
                    """
                    SELECT * FROM outbox_events
                     WHERE status = 'PENDING'
                       AND next_attempt_at <= now()
                     ORDER BY created_at
                     LIMIT :batchSize
                     FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<OutboxEvent> claimDueBatch(@Param("batchSize") int batchSize);

    long countByStatus(OutboxStatus status);

    /** Backlog age, used to alert when the relay falls behind. */
    @Query(
            value = "SELECT min(created_at) FROM outbox_events WHERE status = 'PENDING'",
            nativeQuery = true)
    Instant oldestPendingCreatedAt();

    /** Housekeeping: published rows are an audit trail, but not an unbounded one. */
    long deleteByStatusAndPublishedAtBefore(OutboxStatus status, Instant cutoff);
}
