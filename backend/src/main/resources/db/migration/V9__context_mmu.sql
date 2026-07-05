-- V7 — Context Virtualization: the Paging MMU. Strictly additive.
-- (Numbered V9 because V8 is taken by the Consensus DAG engine.)

-- Per-developer opt-in toggle. OFF by default: the full prompt array is passed
-- to the model exactly as before.
ALTER TABLE developer_auth
    ADD COLUMN v7_mmu_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- L2: semantic stubs. Each row is one paged-out context segment; the summary
-- is what remains inside L1 as a [MEMORY_REF: ...] reference.
CREATE TABLE mmu_semantic_stubs (
    stub_id       VARCHAR(64)  PRIMARY KEY,      -- deterministic content hash
    developer_id  VARCHAR(64)  NOT NULL,
    summary       TEXT         NOT NULL,
    source_tokens INT          NOT NULL DEFAULT 0,
    stub_tokens   INT          NOT NULL DEFAULT 0,
    dirty         BOOLEAN      NOT NULL DEFAULT FALSE,
    version       INT          NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mmu_stubs_dev ON mmu_semantic_stubs (developer_id, created_at);

-- L3: the per-stub append-only event stream. Materialization folds ONLY this
-- stream (CREATED base + UPDATED deltas) — event-sourcing coherence at
-- per-entity granularity, never a whole-history replay.
CREATE TABLE mmu_stub_events (
    id           BIGSERIAL PRIMARY KEY,
    stub_id      VARCHAR(64) NOT NULL,
    developer_id VARCHAR(64) NOT NULL,
    seq          INT         NOT NULL,
    event_type   VARCHAR(16) NOT NULL,   -- CREATED / UPDATED
    content      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mmu_stub_seq UNIQUE (stub_id, seq)
);
CREATE INDEX idx_mmu_events_stub ON mmu_stub_events (stub_id, seq);

-- Profiler telemetry: one row per MMU-processed request.
CREATE TABLE mmu_request_metrics (
    id                  BIGSERIAL PRIMARY KEY,
    developer_id        VARCHAR(64) NOT NULL,
    tokens_without_mmu  INT         NOT NULL,
    tokens_sent         INT         NOT NULL,
    stubs_active        INT         NOT NULL DEFAULT 0,
    stubs_created       INT         NOT NULL DEFAULT 0,
    prefetches          INT         NOT NULL DEFAULT 0,
    page_faults         INT         NOT NULL DEFAULT 0,
    fault_latency_ms    BIGINT      NOT NULL DEFAULT 0,
    materialization_ms  BIGINT      NOT NULL DEFAULT 0,
    dirty_flushes       INT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mmu_metrics_dev ON mmu_request_metrics (developer_id, created_at);
