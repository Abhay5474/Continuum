-- Counterfactual replay: "what would last week's traffic have cost on a
-- different routing policy?" Built as a batch evaluator over the request log,
-- not a time-travel button — replaying one request is a demo, replaying ten
-- thousand before changing a routing threshold is a capability.
--
-- OFF for every tenant. It reads logs and touches nothing on the request path,
-- but it is gated like every other ranked feature.
--
-- No results table: an evaluation is a pure function of the log and the
-- candidate policy, so storing its output would only let it go stale.

CREATE TABLE IF NOT EXISTS counterfactual_setting (
    developer_id VARCHAR(64) PRIMARY KEY,
    enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
