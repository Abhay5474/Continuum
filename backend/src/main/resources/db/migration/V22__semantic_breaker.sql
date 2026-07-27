-- Semantic circuit breaker.
--
-- A classical circuit breaker trips on errors, timeouts and latency. The failure
-- mode that actually hurts a production AI application is the one where the
-- provider is UP, FAST, and WORSE — a silent model update, a quantisation
-- change, a new safety filter refusing benign requests. Chen, Zaharia & Zou
-- (2023) measured GPT-4's accuracy on one task falling 84% -> 51% between two
-- dates on an unchanged API. Nothing in a latency dashboard shows that.
--
-- OFF by default.
--
-- Scoped per tenant on purpose. Drift is a property of the provider, so pooling
-- observations across tenants would detect it sooner — and would also let one
-- account's traffic trip a breaker that reroutes everybody else's. That is a
-- denial-of-service primitive. Each account observes its own traffic and trips
-- its own breaker.

CREATE TABLE IF NOT EXISTS breaker_setting (
    developer_id     VARCHAR(64) PRIMARY KEY,
    enabled          BOOLEAN          NOT NULL DEFAULT FALSE,
    -- Observations needed before a baseline is trusted. Below this the detector
    -- reports WARMING and never trips.
    warmup           INTEGER          NOT NULL DEFAULT 30,
    -- Deviation treated as noise, and accumulated shortfall that means drift.
    slack            DOUBLE PRECISION NOT NULL DEFAULT 0.05,
    threshold        DOUBLE PRECISION NOT NULL DEFAULT 0.75,
    -- How long a tripped breaker stays open before probing.
    cooldown_seconds INTEGER          NOT NULL DEFAULT 300,
    updated_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS breaker_state (
    id            BIGSERIAL PRIMARY KEY,
    developer_id  VARCHAR(64)      NOT NULL,
    provider      VARCHAR(64)      NOT NULL,
    model         VARCHAR(128)     NOT NULL,
    -- CLOSED (serving) | OPEN (rerouted away) | HALF_OPEN (probing)
    state         VARCHAR(16)      NOT NULL DEFAULT 'CLOSED',
    baseline      DOUBLE PRECISION,
    recent_mean   DOUBLE PRECISION,
    accumulated   DOUBLE PRECISION NOT NULL DEFAULT 0,
    observations  BIGINT           NOT NULL DEFAULT 0,
    trips         INTEGER          NOT NULL DEFAULT 0,
    opened_at     TIMESTAMPTZ,
    last_probe_at TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_breaker UNIQUE (developer_id, provider, model)
);

CREATE TABLE IF NOT EXISTS breaker_event (
    id           BIGSERIAL PRIMARY KEY,
    developer_id VARCHAR(64)      NOT NULL,
    provider     VARCHAR(64)      NOT NULL,
    model        VARCHAR(128)     NOT NULL,
    -- TRIPPED | PROBING | RECOVERED | REOPENED
    kind         VARCHAR(16)      NOT NULL,
    baseline     DOUBLE PRECISION,
    observed     DOUBLE PRECISION,
    accumulated  DOUBLE PRECISION,
    detail       TEXT,
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_breaker_state_dev ON breaker_state (developer_id);
CREATE INDEX IF NOT EXISTS idx_breaker_event_dev ON breaker_event (developer_id, created_at DESC);
