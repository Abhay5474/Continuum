-- Graceful degradation: when every model fails, step down explicitly instead of
-- returning nothing.
--
-- OFF for every tenant. Serving a stale or canned answer where a 502 was
-- expected changes what a calling application receives on its worst day, and
-- that has to be a decision.
--
-- Every degraded response says which rung it came from. A degraded answer
-- presented as a normal one is worse than an error, because the caller cannot
-- tell it should retry or warn its user.

CREATE TABLE IF NOT EXISTS degradation_setting (
    developer_id VARCHAR(64) PRIMARY KEY,
    enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS degradation_event (
    id           BIGSERIAL PRIMARY KEY,
    developer_id VARCHAR(64) NOT NULL,
    rung         VARCHAR(16) NOT NULL,
    reason       VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_degradation_dev
    ON degradation_event (developer_id, created_at DESC);
