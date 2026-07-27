-- Routing strategy decisions.
--
-- The contextual bandit has always observed every gateway outcome and has never
-- been allowed to choose. Letting it choose is only defensible if the choice can
-- be checked, so every decision records BOTH what the active strategy picked and
-- what the heuristic scorer would have picked. When they differ, the recorded
-- outcome is a direct, per-request measurement of whether learning helped —
-- rather than an assertion that it did.

CREATE TABLE IF NOT EXISTS routing_strategy_decision (
    id              BIGSERIAL PRIMARY KEY,
    developer_id    VARCHAR(64)      NOT NULL,
    strategy        VARCHAR(16)      NOT NULL,
    context_bucket  VARCHAR(16)      NOT NULL,
    complexity      DOUBLE PRECISION NOT NULL,
    chosen_provider VARCHAR(64)      NOT NULL,
    -- What the heuristic scorer would have chosen for the same request.
    baseline_provider VARCHAR(64),
    -- TRUE when learning actually changed the outcome; the only rows that carry
    -- information about whether the bandit is earning its place.
    diverged        BOOLEAN          NOT NULL DEFAULT FALSE,
    -- Set when the bandit deliberately explored rather than exploited.
    explored        BOOLEAN          NOT NULL DEFAULT FALSE,
    success         BOOLEAN          NOT NULL,
    latency_ms      BIGINT           NOT NULL DEFAULT 0,
    cost            DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_routing_decision_dev
    ON routing_strategy_decision (developer_id, created_at DESC);
