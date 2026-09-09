-- =============================================================================
-- Notification Service schema
--
-- Owned exclusively by this service. It has no CONNECT privilege on the User
-- Service's database and no HTTP client for it, so everything it knows about a
-- user arrives in an event payload. That is what makes the two services
-- genuinely decoupled rather than merely asynchronous.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- notifications
--
-- IDEMPOTENCY. `event_id` carries a UNIQUE constraint, and it is the mechanism
-- that turns the broker's at-least-once delivery into exactly-once *effect*.
--
-- JetStream can legitimately deliver the same event twice: a consumer may crash
-- after sending an email but before acknowledging, or two replicas may briefly
-- race on the same message. Rather than trying to prevent redelivery (which is
-- not possible in a distributed system), the database refuses to record a
-- second notification for an event it has already seen. The handler treats that
-- constraint violation as "someone else already has this one" and acknowledges.
--
-- Note the deliberate choice NOT to use a separate `processed_events` table.
-- With a marker table, a crash between "mark processed" and "send" would make
-- the event look handled while no notification was ever delivered. Making the
-- notification row itself the idempotency record means its `status` tells us
-- exactly how far the work got, so a redelivery can resume rather than skip.
-- -----------------------------------------------------------------------------
CREATE TABLE notifications (
    id                uuid         PRIMARY KEY,

    -- Idempotency key: the id of the event that caused this notification.
    event_id          uuid         NOT NULL,
    event_type        varchar(128) NOT NULL,

    -- Recipient details, copied from the event payload. Denormalised on
    -- purpose: a notification is a record of what was sent to whom at the
    -- time, so it must not change retroactively when a user later edits
    -- their profile.
    recipient_user_id uuid         NOT NULL,
    recipient_email   varchar(320) NOT NULL,
    recipient_name    varchar(200) NOT NULL,

    channel           varchar(32)  NOT NULL,
    subject           varchar(255) NOT NULL,
    body              text         NOT NULL,

    status            varchar(32)  NOT NULL,
    attempts          integer      NOT NULL DEFAULT 0,
    last_error        text,

    -- Ties the notification back to the originating HTTP request, through the
    -- event envelope.
    correlation_id    varchar(128) NOT NULL,

    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    sent_at           timestamptz,

    CONSTRAINT uq_notifications_event_id UNIQUE (event_id),
    CONSTRAINT ck_notifications_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DEAD')),
    CONSTRAINT ck_notifications_channel
        CHECK (channel IN ('EMAIL', 'LOG'))
);

COMMENT ON TABLE notifications IS
    'Delivered and attempted notifications. event_id is UNIQUE, which makes event handling idempotent.';
COMMENT ON COLUMN notifications.event_id IS
    'Idempotency key: a second delivery of the same event cannot create a second notification.';

-- Supports the recipient-facing history endpoint, newest first.
CREATE INDEX idx_notifications_recipient
    ON notifications (recipient_user_id, created_at DESC);

-- Partial index for operational queries: anything not successfully sent.
-- Keeping successful history out of this index keeps it small however much
-- traffic the service has handled.
CREATE INDEX idx_notifications_unsent
    ON notifications (status, updated_at)
    WHERE status <> 'SENT';
