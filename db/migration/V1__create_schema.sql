-- =============================================================================
-- Banking Transaction Processing System - core schema (PostgreSQL 16)
-- =============================================================================
-- Conventions
--   * Money is NUMERIC(19,2), never floating point. Java side uses BigDecimal.
--   * Enumerations are VARCHAR + CHECK (portable, readable in reports, and no
--     JDBC casting issues that native PG enums bring).
--   * Every constraint is named so violations map cleanly to error categories
--     in the application and read clearly in logs.
--   * Timestamps are TIMESTAMPTZ (stored in UTC).
--
-- Status semantics (see docs/design-note.md once written)
--   PENDING   reserved for a future asynchronous mode. The current engine
--             processes each request in one DB transaction and writes only
--             final statuses, so it never stores PENDING.
--   SUCCESS   money moved and committed.
--   REJECTED  a business rule refused it (invalid amount, unknown/inactive
--             account, insufficient balance, same-account transfer).
--   FAILED    a technical problem (deadlock/lock timeout after retries, DB error).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- CUSTOMER - customer master
-- -----------------------------------------------------------------------------
CREATE TABLE customer (
    customer_id  BIGINT GENERATED ALWAYS AS IDENTITY,
    full_name    VARCHAR(100) NOT NULL,
    email        VARCHAR(254) NOT NULL,
    phone        VARCHAR(20),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_customer                   PRIMARY KEY (customer_id),
    CONSTRAINT chk_customer_full_name_blank  CHECK (btrim(full_name) <> ''),
    CONSTRAINT chk_customer_email_format     CHECK (email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$')
);

-- Emails are unique regardless of case ("A@x.com" and "a@x.com" are the same person).
CREATE UNIQUE INDEX uq_customer_email_ci ON customer (lower(email));

COMMENT ON TABLE customer IS 'Customer master data.';


-- -----------------------------------------------------------------------------
-- ACCOUNT - account and current balance
-- -----------------------------------------------------------------------------
-- account_id is the business identifier used by every channel (e.g. ACC000123),
-- matching the sample payload in the assignment. It is also the key used for the
-- deterministic lock order on transfers (always lock the smaller id first).
CREATE TABLE account (
    account_id    VARCHAR(20)   NOT NULL,
    customer_id   BIGINT        NOT NULL,
    account_type  VARCHAR(10)   NOT NULL DEFAULT 'SAVINGS',
    balance       NUMERIC(19,2) NOT NULL DEFAULT 0,
    status        VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_account                    PRIMARY KEY (account_id),
    CONSTRAINT fk_account_customer           FOREIGN KEY (customer_id)
                                             REFERENCES customer (customer_id),
    CONSTRAINT chk_account_id_format         CHECK (account_id ~ '^ACC[0-9]{4,16}$'),
    CONSTRAINT chk_account_type              CHECK (account_type IN ('SAVINGS', 'CURRENT')),
    -- Last line of defence: even if application logic were wrong, the database
    -- refuses to commit a negative balance.
    CONSTRAINT chk_account_balance_non_neg   CHECK (balance >= 0),
    CONSTRAINT chk_account_status            CHECK (status IN ('ACTIVE', 'INACTIVE', 'CLOSED'))
);

-- FK lookup + report 5 (customer -> accounts -> transactions).
-- PostgreSQL does not index FK columns automatically.
CREATE INDEX idx_account_customer_id ON account (customer_id);

COMMENT ON TABLE  account         IS 'Accounts with their current balance. Balance changes only inside a locked DB transaction.';
COMMENT ON COLUMN account.balance IS 'Current balance in INR. Guarded by chk_account_balance_non_neg.';


-- -----------------------------------------------------------------------------
-- TRANSACTION - every request received, with its final status
-- -----------------------------------------------------------------------------
-- Rejected requests are stored *as received* so the audit is honest:
--   * amount may be <= 0 only when status = 'REJECTED';
--   * account columns are NULL when the requested account does not exist
--     (an FK cannot point at a missing row). The raw request, including the
--     unknown account number, is kept in failed_transaction.request_payload.
CREATE TABLE transaction (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY,
    transaction_id          VARCHAR(64)   NOT NULL,
    type                    VARCHAR(12)   NOT NULL,
    amount                  NUMERIC(19,2) NOT NULL,
    source_account_id       VARCHAR(20),
    destination_account_id  VARCHAR(20),
    channel                 VARCHAR(10),
    status                  VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,

    CONSTRAINT pk_transaction                PRIMARY KEY (id),
    -- Idempotency key. The database is the final arbiter: two concurrent
    -- requests with the same id can never both insert.
    CONSTRAINT uq_transaction_transaction_id UNIQUE (transaction_id),
    CONSTRAINT fk_transaction_source         FOREIGN KEY (source_account_id)
                                             REFERENCES account (account_id),
    CONSTRAINT fk_transaction_destination    FOREIGN KEY (destination_account_id)
                                             REFERENCES account (account_id),
    CONSTRAINT chk_transaction_id_blank      CHECK (btrim(transaction_id) <> ''),
    CONSTRAINT chk_transaction_type          CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT chk_transaction_status        CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REJECTED')),
    CONSTRAINT chk_transaction_channel       CHECK (channel IN ('ATM', 'INTERNET', 'MOBILE', 'BRANCH')),
    CONSTRAINT chk_transaction_amount        CHECK (amount > 0 OR status = 'REJECTED'),
    -- A successful transaction must reference exactly the accounts its type needs.
    CONSTRAINT chk_transaction_success_shape CHECK (
        status <> 'SUCCESS'
        OR (type = 'DEPOSIT'    AND source_account_id IS NULL     AND destination_account_id IS NOT NULL)
        OR (type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND destination_account_id IS NULL)
        OR (type = 'TRANSFER'   AND source_account_id IS NOT NULL AND destination_account_id IS NOT NULL
                                AND source_account_id <> destination_account_id)
    ),
    -- Finished <=> has a completion time, and it cannot precede creation.
    CONSTRAINT chk_transaction_completed_at  CHECK ((status = 'PENDING') = (completed_at IS NULL)),
    CONSTRAINT chk_transaction_time_order    CHECK (completed_at IS NULL OR completed_at >= created_at)
);

-- Account history and "transactions per account" (reports 1, 4, 5).
-- Partial indexes: deposits have no source and withdrawals have no destination,
-- so NULLs are excluded - smaller indexes, cheaper writes on the hot path.
-- An equality predicate (source_account_id = ?) lets the planner use them.
CREATE INDEX idx_transaction_source_created
    ON transaction (source_account_id, created_at)
    WHERE source_account_id IS NOT NULL;

CREATE INDEX idx_transaction_destination_created
    ON transaction (destination_account_id, created_at)
    WHERE destination_account_id IS NOT NULL;

-- Report 3 (daily amount by type over a date range). INCLUDE makes it a
-- covering index so the report can be answered by an index-only scan.
CREATE INDEX idx_transaction_created_type
    ON transaction (created_at, type) INCLUDE (amount, status);

COMMENT ON TABLE  transaction                IS 'One row per unique transaction request and its final outcome.';
COMMENT ON COLUMN transaction.transaction_id IS 'Client-supplied idempotency key (e.g. TXN100234).';


-- -----------------------------------------------------------------------------
-- TRANSACTION_AUDIT - processing trail (business audit, not log lines)
-- -----------------------------------------------------------------------------
-- One row per processing event. Duplicate attempts are recorded here (they
-- cannot get their own transaction row because the id is already taken).
CREATE TABLE transaction_audit (
    audit_id                   BIGINT GENERATED ALWAYS AS IDENTITY,
    transaction_id             VARCHAR(64)   NOT NULL,
    event                      VARCHAR(20)   NOT NULL,
    thread_name                VARCHAR(100)  NOT NULL,
    attempt                    SMALLINT      NOT NULL DEFAULT 1,
    duration_ms                NUMERIC(12,3) NOT NULL DEFAULT 0,
    source_balance_after       NUMERIC(19,2),
    destination_balance_after  NUMERIC(19,2),
    details                    VARCHAR(500),
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_transaction_audit          PRIMARY KEY (audit_id),
    CONSTRAINT fk_transaction_audit_txn      FOREIGN KEY (transaction_id)
                                             REFERENCES transaction (transaction_id),
    CONSTRAINT chk_transaction_audit_event   CHECK (event IN ('SUCCESS', 'REJECTED', 'FAILED',
                                                              'DUPLICATE_ATTEMPT', 'RETRY')),
    CONSTRAINT chk_transaction_audit_attempt CHECK (attempt >= 1),
    CONSTRAINT chk_transaction_audit_dur     CHECK (duration_ms >= 0)
);

-- FK lookup: "show the full audit trail for transaction X", in order.
CREATE INDEX idx_transaction_audit_txn_created ON transaction_audit (transaction_id, created_at);

COMMENT ON TABLE transaction_audit IS 'Business audit trail: who processed what, on which thread, how long it took, resulting balances.';


-- -----------------------------------------------------------------------------
-- FAILED_TRANSACTION - failure details for investigation
-- -----------------------------------------------------------------------------
-- At most one row per transaction: its final failure category and reason.
CREATE TABLE failed_transaction (
    failure_id       BIGINT GENERATED ALWAYS AS IDENTITY,
    transaction_id   VARCHAR(64)  NOT NULL,
    error_category   VARCHAR(30)  NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    request_payload  JSONB,
    failed_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_failed_transaction         PRIMARY KEY (failure_id),
    -- Also serves as the FK index.
    CONSTRAINT uq_failed_transaction_txn     UNIQUE (transaction_id),
    CONSTRAINT fk_failed_transaction_txn     FOREIGN KEY (transaction_id)
                                             REFERENCES transaction (transaction_id),
    CONSTRAINT chk_failed_transaction_cat    CHECK (error_category IN (
                                                 'INVALID_TRANSACTION',
                                                 'ACCOUNT_NOT_FOUND',
                                                 'ACCOUNT_INACTIVE',
                                                 'INSUFFICIENT_BALANCE',
                                                 'CONCURRENCY_CONFLICT',
                                                 'SYSTEM_ERROR')),
    CONSTRAINT chk_failed_transaction_reason CHECK (btrim(reason) <> '')
);

-- Report 2 (failed transactions grouped by reason).
CREATE INDEX idx_failed_transaction_category ON failed_transaction (error_category);

COMMENT ON TABLE  failed_transaction                 IS 'Final failure category and reason for every FAILED or REJECTED transaction.';
COMMENT ON COLUMN failed_transaction.request_payload IS 'Raw request as received (no credentials); keeps unknown account numbers that the FK columns cannot hold.';
