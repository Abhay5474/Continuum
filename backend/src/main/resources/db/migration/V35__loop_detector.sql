-- Agent loop detector: an agent that has lost the thread does not crash, it
-- keeps billing.
--
-- OFF for every tenant. Halting a run is a decision; a false positive stops an
-- agent that was working, which is the more expensive mistake.

CREATE TABLE IF NOT EXISTS loop_setting (
    developer_id VARCHAR(64) PRIMARY KEY,
    enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    -- MONITOR records; HALT also stops the run.
    mode         VARCHAR(16) NOT NULL DEFAULT 'MONITOR',
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS loop_event (
    id           BIGSERIAL PRIMARY KEY,
    developer_id VARCHAR(64)      NOT NULL,
    workflow_id  VARCHAR(64),
    kind         VARCHAR(16)      NOT NULL,
    step_index   INTEGER          NOT NULL,
    confidence   DOUBLE PRECISION NOT NULL DEFAULT 0,
    reason       VARCHAR(500),
    evidence     VARCHAR(1000),
    halted       BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_loop_event_dev ON loop_event (developer_id, created_at DESC);
