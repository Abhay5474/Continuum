-- V6 — Consensus DAG Engine (verifiable AI reliability layer). Strictly additive.
-- (Numbered V8 because V7 is taken by God Mode.)

-- Per-developer opt-in toggle. OFF by default: the gateway runs its exact
-- legacy path unless the developer flips this in the portal.
ALTER TABLE developer_auth
    ADD COLUMN v6_dag_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Projection of completed/running DAG runs for the trace UI (the event log in
-- workflow_events remains the source of truth; these rows are derived).
CREATE TABLE dag_runs (
    id               BIGSERIAL PRIMARY KEY,
    workflow_id      VARCHAR(64)  NOT NULL UNIQUE,
    developer_id     VARCHAR(64)  NOT NULL,
    prompt           TEXT,
    status           VARCHAR(20)  NOT NULL,
    final_confidence DOUBLE PRECISION,
    uncertainty      VARCHAR(12),
    verdict          TEXT,
    risk_flags_json  TEXT,
    claim_count      INT          NOT NULL DEFAULT 0,
    node_count       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);
CREATE INDEX idx_dag_runs_dev ON dag_runs (developer_id, created_at);

CREATE TABLE dag_nodes (
    id            BIGSERIAL PRIMARY KEY,
    workflow_id   VARCHAR(64) NOT NULL,
    node_key      VARCHAR(64) NOT NULL,
    node_type     VARCHAR(20) NOT NULL,  -- PLANNER / SOLVER / VERIFIER / CONFLICT / AGGREGATOR / SYNTHESIS
    claim_id      INT,
    label         VARCHAR(300),
    status        VARCHAR(20) NOT NULL,  -- PASS / FAIL / UNCERTAIN / DONE
    validity      DOUBLE PRECISION,
    output_json   TEXT,
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    CONSTRAINT uq_dag_node UNIQUE (workflow_id, node_key)
);
CREATE INDEX idx_dag_nodes_wf ON dag_nodes (workflow_id);

CREATE TABLE dag_edges (
    id           BIGSERIAL PRIMARY KEY,
    workflow_id  VARCHAR(64) NOT NULL,
    from_key     VARCHAR(64) NOT NULL,
    to_key       VARCHAR(64) NOT NULL,
    edge_type    VARCHAR(16) NOT NULL,   -- FLOW / SUPPORTS / CONTRADICTS / DEPENDS
    weight       DOUBLE PRECISION NOT NULL DEFAULT 0.5
);
CREATE INDEX idx_dag_edges_wf ON dag_edges (workflow_id);
