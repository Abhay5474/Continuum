-- Saga compensation: durable execution guarantees each step runs once, not that
-- the set of steps is all-or-nothing. A workflow that charged a card and then
-- failed to ship has taken money and is holding stock.
--
-- OFF for every tenant. Rollback issues real calls to real systems; nobody
-- should discover it by being opted in.

CREATE TABLE IF NOT EXISTS saga_setting (
    developer_id VARCHAR(64) PRIMARY KEY,
    enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS saga_event (
    id            BIGSERIAL PRIMARY KEY,
    developer_id  VARCHAR(64) NOT NULL,
    workflow_id   VARCHAR(64),
    definition    VARCHAR(120),
    failed_step   VARCHAR(120),
    compensated   VARCHAR(1000),
    -- The column an operator actually needs: what is still out there.
    uncompensated VARCHAR(1000),
    complete      BOOLEAN     NOT NULL DEFAULT FALSE,
    summary       VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_saga_event_dev ON saga_event (developer_id, created_at DESC);
