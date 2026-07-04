-- V5.1.1 — Determinism Divergence Auto-Healing (Paradox Resolution Engine).
-- Strictly additive: new tables only, nothing existing is altered.

-- The durable resolution ledger. One row per healed code command sequence of a
-- workflow instance. Future replays of that instance re-apply the exact same
-- virtualization map, keeping execution deterministic forever.
CREATE TABLE workflow_healing_logs (
    id                          BIGSERIAL PRIMARY KEY,
    workflow_id                 VARCHAR(64) NOT NULL REFERENCES workflow_instances (workflow_id),
    divergence_sequence_number  BIGINT      NOT NULL,
    resolution_type             VARCHAR(32) NOT NULL,
    virtualized_payload_json    TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_healing_wf_seq UNIQUE (workflow_id, divergence_sequence_number)
);

CREATE INDEX idx_healing_workflow ON workflow_healing_logs (workflow_id);
CREATE INDEX idx_healing_created ON workflow_healing_logs (created_at);

-- Observability feed of every resolved divergence paradox (denormalized so the
-- control plane can aggregate without touching the per-instance ledger).
CREATE TABLE autopilot_divergence_resolutions (
    id                BIGSERIAL PRIMARY KEY,
    workflow_id       VARCHAR(64)  NOT NULL,
    workflow_type     VARCHAR(200),
    resolution_type   VARCHAR(32)  NOT NULL,
    code_sequence     BIGINT       NOT NULL,
    history_sequence  BIGINT,
    detail            TEXT,
    resolved_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_divergence_res_wf ON autopilot_divergence_resolutions (workflow_id);
CREATE INDEX idx_divergence_res_at ON autopilot_divergence_resolutions (resolved_at);
