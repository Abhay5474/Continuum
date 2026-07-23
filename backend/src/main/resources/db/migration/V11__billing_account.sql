-- V11 — Billing & usage metering + account management. Strictly additive.

-- Per-developer billing plan + monthly token quota. Usage itself is derived
-- from gateway_requests (already tracked), so no usage rows are stored here.
CREATE TABLE developer_billing (
    developer_id        VARCHAR(64) PRIMARY KEY REFERENCES developers (id),
    plan                VARCHAR(16) NOT NULL DEFAULT 'FREE',
    monthly_token_quota BIGINT      NOT NULL DEFAULT 100000,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- API keys gain a human-readable label (additive, nullable).
ALTER TABLE developer_api_keys
    ADD COLUMN label VARCHAR(120);

-- Lightweight team invites (mock — generates an invite token/link).
CREATE TABLE team_invites (
    id           BIGSERIAL PRIMARY KEY,
    developer_id VARCHAR(64)  NOT NULL,
    email        VARCHAR(200) NOT NULL,
    token        VARCHAR(64)  NOT NULL UNIQUE,
    accepted     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_team_invites_dev ON team_invites (developer_id, created_at);
