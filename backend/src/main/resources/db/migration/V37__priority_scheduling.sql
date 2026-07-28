-- Priority and deadline scheduling: admission control answers "is there room",
-- this answers "for whom". FIFO is fair and it is the wrong kind of fair.
--
-- OFF for every tenant. Turning it on changes which request waits, and that
-- should be a decision.
--
-- No queue table by design. A waiter exists only while its request is blocked,
-- so there is nothing to persist and nothing to recover after a restart.

CREATE TABLE IF NOT EXISTS scheduling_setting (
    developer_id VARCHAR(64) PRIMARY KEY,
    enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
