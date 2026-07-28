-- Congestion-controlled admission: infer provider capacity from latency and
-- hold concurrency there, instead of discovering it through 429s.
--
-- OFF for every tenant, existing and new. Admission control decides which
-- requests reach a provider at all, so turning it on changes what a live
-- application experiences under load — a decision, never an upgrade.
--
-- One column on purpose. The concurrency limit is inferred, so a configurable
-- maximum would reintroduce the typed guess this replaces.

CREATE TABLE IF NOT EXISTS admission_setting (
    developer_id VARCHAR(64)  PRIMARY KEY,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
