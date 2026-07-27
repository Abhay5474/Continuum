-- Semantic uncertainty (Farquhar, Kossen, Kuhn & Gal — Nature, 2024).
--
-- Sample the same question k times, cluster the answers by meaning rather than
-- by wording, and take entropy over the meaning classes. A model that says the
-- same thing five different ways is certain; one that says five different things
-- is confabulating, and token-level confidence cannot tell them apart.
--
-- The measurement is stored so the console can show WHY a confidence was low —
-- the actual disagreeing answers — rather than only the number.

CREATE TABLE IF NOT EXISTS uncertainty_setting (
    developer_id   VARCHAR(64) PRIMARY KEY,
    -- OFF | ON_DEMAND (only when the request asks) | ADAPTIVE (only when the
    -- cascade judge is unsure) | ALWAYS
    mode           VARCHAR(16)      NOT NULL DEFAULT 'OFF',
    -- How many samples. Cost scales linearly with this, so it is a real dial.
    samples        INTEGER          NOT NULL DEFAULT 3,
    -- Sampling temperature. At 0 every sample is identical and the entropy is
    -- always 0, which measures nothing.
    temperature    DOUBLE PRECISION NOT NULL DEFAULT 0.7,
    -- Answers below this confidence are flagged in the response.
    low_confidence DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    updated_at     TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS uncertainty_measurement (
    id             BIGSERIAL PRIMARY KEY,
    developer_id   VARCHAR(64)      NOT NULL,
    prompt         TEXT,
    model          VARCHAR(128),
    samples        INTEGER          NOT NULL DEFAULT 0,
    clusters       INTEGER          NOT NULL DEFAULT 0,
    -- Raw semantic entropy in nats, and normalised to [0,1] by log(k) so it is
    -- comparable across different sample counts.
    entropy        DOUBLE PRECISION NOT NULL DEFAULT 0,
    normalised     DOUBLE PRECISION NOT NULL DEFAULT 0,
    confidence     DOUBLE PRECISION NOT NULL DEFAULT 0,
    -- The clusters themselves, so the console can show the disagreement.
    clusters_json  TEXT,
    extra_cost     DOUBLE PRECISION NOT NULL DEFAULT 0,
    extra_ms       BIGINT           NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_uncertainty_dev
    ON uncertainty_measurement (developer_id, created_at DESC);
