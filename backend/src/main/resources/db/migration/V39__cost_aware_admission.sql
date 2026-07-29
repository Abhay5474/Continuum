-- Cost-aware admission: rate limiting by what a request consumes rather than by
-- how many there are. A fifty-step agent carrying twenty thousand tokens is not
-- the same as "hello", and under a request-count limit the second subsidises the
-- first.
--
-- OFF for every tenant. Turning it on can refuse traffic that the request-count
-- limiter would have admitted, so it is a deliberate choice.

CREATE TABLE IF NOT EXISTS cost_admission_setting (
    developer_id     VARCHAR(64) PRIMARY KEY,
    enabled          BOOLEAN     NOT NULL DEFAULT FALSE,
    requests_per_min INTEGER     NOT NULL DEFAULT 120,
    -- The resource that actually costs money.
    tokens_per_min   INTEGER     NOT NULL DEFAULT 120000,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
