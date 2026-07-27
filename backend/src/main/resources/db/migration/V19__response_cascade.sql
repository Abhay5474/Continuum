-- Verify-then-Escalate cascade (FrugalGPT, Chen/Zaharia/Zou 2023; UCCI 2026).
--
-- Answer with the cheapest capable model, judge the answer, and pay for the
-- expensive model only when the judge says the cheap one did not manage it.
--
-- The decision table exists so the saving can be proven rather than claimed.
-- Two columns carry the weight:
--
--   * `agreed_with_strong` — when an escalation happened, whether the strong
--     model's answer actually differed from the cheap one. If it agreed, the
--     escalation was wasted and the threshold is too high. This is a real
--     self-supervised label: no human, no ground truth, no benchmark.
--
--   * `audit` — rows where BOTH tiers ran deliberately on a sampled slice,
--     which is the only way to measure the escalations the judge MISSED.
--     Without it a cascade can look like a triumph while quietly shipping
--     worse answers.

CREATE TABLE IF NOT EXISTS cascade_setting (
    developer_id     VARCHAR(64) PRIMARY KEY,
    enabled          BOOLEAN          NOT NULL DEFAULT FALSE,
    -- Accept the cheap answer at or above this calibrated confidence.
    threshold        DOUBLE PRECISION NOT NULL DEFAULT 0.75,
    -- Fraction of traffic that runs both tiers to measure missed escalations.
    audit_rate       DOUBLE PRECISION NOT NULL DEFAULT 0.05,
    -- Alarm when escalation exceeds this; a miscalibrated judge that escalates
    -- everything makes the bill go UP, which is the failure mode to catch.
    escalation_cap   DOUBLE PRECISION NOT NULL DEFAULT 0.60,
    updated_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS cascade_decision (
    id                 BIGSERIAL PRIMARY KEY,
    developer_id       VARCHAR(64)      NOT NULL,
    complexity         DOUBLE PRECISION NOT NULL DEFAULT 0,
    raw_score          DOUBLE PRECISION NOT NULL DEFAULT 0,
    confidence         DOUBLE PRECISION NOT NULL DEFAULT 0,
    threshold          DOUBLE PRECISION NOT NULL DEFAULT 0,
    escalated          BOOLEAN          NOT NULL DEFAULT FALSE,
    audit              BOOLEAN          NOT NULL DEFAULT FALSE,
    -- NULL unless both tiers ran. TRUE means the strong model said the same
    -- thing, i.e. the cheap answer was fine all along.
    agreed_with_strong BOOLEAN,
    similarity         DOUBLE PRECISION,
    concerns           TEXT,
    cheap_model        VARCHAR(128),
    strong_model       VARCHAR(128),
    cheap_cost         DOUBLE PRECISION NOT NULL DEFAULT 0,
    strong_cost        DOUBLE PRECISION NOT NULL DEFAULT 0,
    cheap_latency_ms   BIGINT           NOT NULL DEFAULT 0,
    total_latency_ms   BIGINT           NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cascade_decision_dev
    ON cascade_decision (developer_id, created_at DESC);
