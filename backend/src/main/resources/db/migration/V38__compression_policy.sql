-- Adaptive compression policy: LLMLingua's budget controller allocates
-- different compression ratios to different regions of a prompt, because
-- instructions, demonstrations and the question do not carry information at the
-- same density.
--
-- OFF for every tenant. When off the compressor applies one ratio to everything,
-- exactly as it did before this table existed.

CREATE TABLE IF NOT EXISTS compression_policy_setting (
    developer_id VARCHAR(64) PRIMARY KEY,
    enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
