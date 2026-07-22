-- V8 research features — Prompt Compression + Prompt Firewall. Strictly additive.
-- (Migration numbered V10; V8/V9 are taken by the DAG and MMU features.)

-- Per-developer opt-in toggles. OFF by default: prompts are sent verbatim and
-- unscanned, exactly as before.
ALTER TABLE developer_auth
    ADD COLUMN v8_compression_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE developer_auth
    ADD COLUMN v8_firewall_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Prompt-compression telemetry: one row per compressed request (before/after
-- token counts drive the research metric).
CREATE TABLE compression_metrics (
    id                 BIGSERIAL PRIMARY KEY,
    developer_id       VARCHAR(64) NOT NULL,
    original_tokens    INT         NOT NULL,
    compressed_tokens  INT         NOT NULL,
    target_ratio       DOUBLE PRECISION NOT NULL,
    achieved_ratio     DOUBLE PRECISION NOT NULL,
    protected_spans    INT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_compression_dev ON compression_metrics (developer_id, created_at);

-- Firewall events: one row per detection (redaction or injection block).
CREATE TABLE firewall_events (
    id            BIGSERIAL PRIMARY KEY,
    developer_id  VARCHAR(64) NOT NULL,
    direction     VARCHAR(10) NOT NULL,   -- INBOUND / OUTBOUND
    category      VARCHAR(32) NOT NULL,   -- EMAIL / CREDIT_CARD / SSN / API_KEY / PROMPT_INJECTION / ...
    action        VARCHAR(16) NOT NULL,   -- REDACTED / FLAGGED / BLOCKED
    match_count   INT         NOT NULL DEFAULT 1,
    detail        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_firewall_dev ON firewall_events (developer_id, created_at);
