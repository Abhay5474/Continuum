-- Decision provenance: the same explanations Continuum already produces, as
-- data rather than as a sentence.
--
-- OFF for every tenant. Recording is cheap but not free, and a request path is
-- the wrong place to add unasked-for writes.
--
-- One row per decision rather than one blob per request: the questions worth
-- asking are "how often did the cascade escalate" and "what did routing cost
-- me", and both are aggregations over decisions, not over requests.

CREATE TABLE IF NOT EXISTS decision_record (
    id           BIGSERIAL PRIMARY KEY,
    developer_id VARCHAR(64)      NOT NULL,
    request_id   VARCHAR(64)      NOT NULL,
    seq          INTEGER          NOT NULL,
    stage        VARCHAR(24)      NOT NULL,
    choice       VARCHAR(200),
    reason       VARCHAR(500),
    alternatives VARCHAR(500),
    cost_delta   DOUBLE PRECISION NOT NULL DEFAULT 0,
    latency_ms   BIGINT           NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_decision_dev ON decision_record (developer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_decision_request ON decision_record (request_id, seq);

CREATE TABLE IF NOT EXISTS provenance_setting (
    developer_id VARCHAR(64) PRIMARY KEY,
    enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
