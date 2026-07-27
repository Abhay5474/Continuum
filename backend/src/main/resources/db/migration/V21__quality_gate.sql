-- Response quality gate.
--
-- Checks a finished answer against the request that asked for it, and acts on
-- the verdict. OFF by default, like every feature on this roadmap.
--
-- MONITOR is the mode that matters. LLM-as-judge is documented as biased and
-- inline correction can make answers worse, so a gate that starts by silently
-- rewriting production traffic is indefensible. In MONITOR the gate runs, records
-- exactly what it WOULD have done, and changes nothing — so the decision to
-- enforce is made against evidence from your own traffic rather than a promise.

CREATE TABLE IF NOT EXISTS quality_gate_setting (
    developer_id      VARCHAR(64) PRIMARY KEY,
    -- OFF | MONITOR (check and record, never alter) | ENFORCE (check and repair)
    mode              VARCHAR(16)      NOT NULL DEFAULT 'OFF',
    -- Answers scoring below this are acted on.
    threshold         DOUBLE PRECISION NOT NULL DEFAULT 0.6,
    -- A repair costs a second call. One attempt only: a gate that loops is a
    -- gate that can spend without bound on an answer it will never like.
    max_repairs       INTEGER          NOT NULL DEFAULT 1,
    -- Hard ceiling on the repair. Past it the original answer is returned
    -- unchanged, because a slow correct answer is worse than a fast flawed one
    -- for anything interactive.
    budget_ms         INTEGER          NOT NULL DEFAULT 4000,
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS quality_gate_check (
    id                BIGSERIAL PRIMARY KEY,
    developer_id      VARCHAR(64)      NOT NULL,
    mode              VARCHAR(16)      NOT NULL,
    score             DOUBLE PRECISION NOT NULL DEFAULT 0,
    threshold         DOUBLE PRECISION NOT NULL DEFAULT 0,
    action            VARCHAR(16)      NOT NULL,
    -- What actually happened. In MONITOR this is always NONE while `action`
    -- records the intent — the difference between the two columns is the whole
    -- point of the mode.
    applied           VARCHAR(16)      NOT NULL DEFAULT 'NONE',
    defects           TEXT,
    dimensions_json   TEXT,
    model             VARCHAR(128),
    -- Kept only when a repair actually ran, so the before/after is inspectable.
    original_answer   TEXT,
    repaired_answer   TEXT,
    -- Did the repair fix the defects it was told about?
    repair_improved   BOOLEAN,
    repair_score      DOUBLE PRECISION,
    extra_cost        DOUBLE PRECISION NOT NULL DEFAULT 0,
    extra_ms          BIGINT           NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_quality_check_dev
    ON quality_gate_check (developer_id, created_at DESC);
