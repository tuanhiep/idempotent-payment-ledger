-- =============================================================================
-- Idempotent Payment Ledger - PostgreSQL Migration
-- =============================================================================
-- Invariant: one logical payment attempt maps to one accepted outcome and one
-- balanced ledger transaction. This migration is exercised by local Docker
-- PostgreSQL and by Testcontainers-backed integration tests.
-- =============================================================================

CREATE TABLE IF NOT EXISTS idempotency_records (
    id              BIGSERIAL       NOT NULL,
    tenant_id       TEXT            NOT NULL,
    idempotency_key TEXT            NOT NULL,
    payload_hash    TEXT            NOT NULL,
    request_body    TEXT            NOT NULL,
    response_body   TEXT,
    status          VARCHAR(16)     NOT NULL
                        CHECK (status IN ('PROCESSING', 'ACCEPTED', 'FAILED')),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_records_lookup
    ON idempotency_records (tenant_id, idempotency_key);

CREATE INDEX IF NOT EXISTS idx_idempotency_records_expires
    ON idempotency_records (expires_at)
    WHERE status = 'ACCEPTED';

CREATE TABLE IF NOT EXISTS payments (
    payment_id          TEXT            NOT NULL,
    tenant_id           TEXT            NOT NULL,
    idempotency_key     TEXT            NOT NULL,
    payer_account_id    TEXT            NOT NULL,
    merchant_account_id TEXT            NOT NULL,
    amount              NUMERIC(19, 4)  NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)      NOT NULL,
    status              VARCHAR(16)     NOT NULL
                            CHECK (status IN ('ACCEPTED', 'REVERSED')),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_payments PRIMARY KEY (payment_id)
);

CREATE INDEX IF NOT EXISTS idx_payments_idempotency_key
    ON payments (tenant_id, idempotency_key);

CREATE INDEX IF NOT EXISTS idx_payments_payer
    ON payments (payer_account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payments_merchant
    ON payments (merchant_account_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ledger_transactions (
    transaction_id          TEXT        NOT NULL,
    payment_id              TEXT        NOT NULL
                                REFERENCES payments (payment_id),
    posting_rule            TEXT        NOT NULL,
    posting_rule_version    INTEGER     NOT NULL DEFAULT 1,
    status                  VARCHAR(16) NOT NULL
                                CHECK (status IN ('POSTED', 'REVERSED')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_ledger_transactions PRIMARY KEY (transaction_id)
);

CREATE INDEX IF NOT EXISTS idx_ledger_transactions_payment_id
    ON ledger_transactions (payment_id);

CREATE TABLE IF NOT EXISTS ledger_entries (
    entry_id        TEXT            NOT NULL,
    transaction_id  TEXT            NOT NULL
                        REFERENCES ledger_transactions (transaction_id),
    account_id      TEXT            NOT NULL,
    entry_type      VARCHAR(8)      NOT NULL
                        CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount          NUMERIC(19, 4)  NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3)      NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_ledger_entries PRIMARY KEY (entry_id)
);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_transaction_id
    ON ledger_entries (transaction_id);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_id
    ON ledger_entries (account_id, created_at DESC);

CREATE TABLE IF NOT EXISTS outbox_events (
    event_id        BIGSERIAL       NOT NULL,
    aggregate_id    TEXT            NOT NULL,
    event_type      TEXT            NOT NULL,
    payload         TEXT            NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,

    CONSTRAINT pk_outbox_events PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_unpublished
    ON outbox_events (created_at ASC)
    WHERE published_at IS NULL;
