-- =============================================================================
-- User Service schema
--
-- Owned exclusively by this service. No other service has CONNECT privilege on
-- this database (see infra/postgres/init), so the only way user data reaches
-- another service is the published event contract.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- users
--
-- `email` is stored already lower-cased and carries a plain UNIQUE constraint,
-- which makes the uniqueness check index-backed and case-insensitive without
-- needing the citext extension. Normalisation happens in one place in the
-- domain model so the invariant cannot be bypassed by a new code path.
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id            uuid         PRIMARY KEY,
    email         varchar(320) NOT NULL,
    password_hash varchar(255) NOT NULL,
    full_name     varchar(200) NOT NULL,
    status        varchar(32)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    -- Optimistic locking: two concurrent profile updates cannot silently
    -- overwrite one another.
    version       bigint       NOT NULL DEFAULT 0,

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email))
);

COMMENT ON TABLE users IS 'Registered accounts. Passwords are Argon2id hashes, never reversible.';

-- -----------------------------------------------------------------------------
-- user_roles
--
-- A separate table rather than a comma-separated column, so a role can be
-- granted or revoked without rewriting the user row, and so the set is
-- constrained by the database.
-- -----------------------------------------------------------------------------
CREATE TABLE user_roles (
    user_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    varchar(32) NOT NULL,

    PRIMARY KEY (user_id, role),
    CONSTRAINT ck_user_roles_role CHECK (role IN ('USER', 'ADMIN'))
);

-- -----------------------------------------------------------------------------
-- refresh_tokens
--
-- Refresh tokens are opaque 256-bit random values. Only their SHA-256 hash is
-- stored: a database disclosure therefore does not hand an attacker usable
-- credentials. SHA-256 (not Argon2) is correct here because the input is
-- already high-entropy and not brute-forceable, so a slow KDF would add
-- latency without adding security.
--
-- `family_id` groups every token descended from one login. Rotation replaces a
-- token on each use; if an already-rotated token is ever presented again, the
-- whole family is revoked. That converts a stolen-token replay into a detected
-- event plus a forced re-authentication, instead of silent parallel access.
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id              uuid         PRIMARY KEY,
    user_id         uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- varchar, not char: PostgreSQL gains nothing from a fixed-length type and
    -- CHAR pads values with spaces, which would corrupt an exact-match lookup.
    token_hash      varchar(64)  NOT NULL,
    family_id       uuid         NOT NULL,
    issued_at       timestamptz  NOT NULL DEFAULT now(),
    expires_at      timestamptz  NOT NULL,
    revoked_at      timestamptz,
    revoked_reason  varchar(64),
    replaced_by     uuid,
    client_ip       varchar(64),
    user_agent      varchar(255),

    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
-- Supports the scheduled purge of expired tokens.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- -----------------------------------------------------------------------------
-- outbox_events  -- the transactional outbox
--
-- This table is the heart of reliable event publishing. A domain change and the
-- event announcing it are inserted in the SAME database transaction, so the two
-- cannot disagree: there is no window in which a user exists without its event,
-- or an event is emitted for a transaction that later rolled back.
--
-- A background relay then moves rows to the broker. Because publishing happens
-- after the commit, delivery is at-least-once; the event id is used as the
-- broker's deduplication key so a redundant republish is collapsed rather than
-- duplicated.
--
-- `payload` holds the complete serialised envelope, which keeps the relay free
-- of domain knowledge: it ships bytes and records the outcome.
-- -----------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id              uuid         PRIMARY KEY,
    aggregate_type  varchar(64)  NOT NULL,
    aggregate_id    uuid         NOT NULL,
    event_type      varchar(128) NOT NULL,
    -- Schema version of the payload, carried as a message header so a consumer can
    -- branch on it without deserialising the body.
    data_version    integer      NOT NULL DEFAULT 1,
    subject         varchar(256) NOT NULL,
    payload         jsonb        NOT NULL,
    correlation_id  varchar(128) NOT NULL,
    status          varchar(16)  NOT NULL DEFAULT 'PENDING',
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    published_at    timestamptz,

    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Partial index: the relay only ever scans due, unpublished rows, so keeping
-- published history in the table costs nothing at read time.
CREATE INDEX idx_outbox_due
    ON outbox_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';

-- Supports alerting on rows that exhausted their retries.
CREATE INDEX idx_outbox_failed
    ON outbox_events (created_at)
    WHERE status = 'FAILED';

COMMENT ON TABLE outbox_events IS
    'Transactional outbox: domain events committed atomically with the change that caused them.';
