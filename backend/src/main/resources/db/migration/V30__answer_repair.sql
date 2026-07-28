-- Answer Repair Engine: diagnose why an answer is deficient and fix that
-- specific defect, instead of re-rolling the whole request and hoping.
--
-- OFF for every tenant, existing and new. With it off the quality gate's
-- existing single-shot repair is unchanged, so turning this on is the only
-- thing that alters behaviour.
--
-- Attempts are stored — including the discarded ones. Those are the evidence
-- that the guard against making an answer worse is doing something, and the
-- only way to tell a repair engine that helps from one expensively churning.

ALTER TABLE quality_gate_setting
    ADD COLUMN IF NOT EXISTS repair_engine_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS repair_attempt (
    id           BIGSERIAL PRIMARY KEY,
    developer_id VARCHAR(64)      NOT NULL,
    model        VARCHAR(120),
    attempt      INTEGER          NOT NULL,
    -- Which kind of defect this attempt was aimed at.
    strategy     VARCHAR(32)      NOT NULL,
    defects      VARCHAR(1000),
    score_before DOUBLE PRECISION NOT NULL,
    score_after  DOUBLE PRECISION NOT NULL,
    kept         BOOLEAN          NOT NULL,
    note         VARCHAR(300),
    cost         DOUBLE PRECISION NOT NULL DEFAULT 0,
    latency_ms   BIGINT           NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_repair_attempt_dev
    ON repair_attempt (developer_id, created_at DESC);
