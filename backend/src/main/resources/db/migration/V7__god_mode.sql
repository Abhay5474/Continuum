-- V5 "God Mode" — autonomous memory & policy engine. Strictly additive.
-- Per-developer opt-in config (a dedicated table rather than a column on the
-- security-critical developer_auth row: same per-developer toggle semantics,
-- zero churn on the auth entity).
CREATE TABLE god_mode_config (
    developer_id        VARCHAR(64) PRIMARY KEY REFERENCES developers (id),
    enabled             BOOLEAN     NOT NULL DEFAULT FALSE,
    memact_enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    twin_gate_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
    context_budget_tokens INT       NOT NULL DEFAULT 8000,
    working_ttl_minutes INT         NOT NULL DEFAULT 120,
    episodic_ttl_days   INT         NOT NULL DEFAULT 14,
    semantic_ttl_days   INT         NOT NULL DEFAULT 90,
    max_working_items   INT         NOT NULL DEFAULT 500,
    max_episodic_items  INT         NOT NULL DEFAULT 2000,
    max_semantic_nodes  INT         NOT NULL DEFAULT 5000,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tier 1: live working context (hot, minutes-to-hours TTL).
CREATE TABLE memory_working (
    id            BIGSERIAL PRIMARY KEY,
    developer_id  VARCHAR(64)  NOT NULL,
    session_id    VARCHAR(128) NOT NULL,
    role          VARCHAR(24)  NOT NULL,
    content       TEXT         NOT NULL,
    tokens        INT          NOT NULL DEFAULT 0,
    expires_at    TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mem_working_dev ON memory_working (developer_id, session_id);
CREATE INDEX idx_mem_working_exp ON memory_working (expires_at);

-- Tier 2: episodic summaries (compressed working memory, days TTL).
CREATE TABLE memory_episodic (
    id             BIGSERIAL PRIMARY KEY,
    developer_id   VARCHAR(64)  NOT NULL,
    session_id     VARCHAR(128),
    summary_text   TEXT         NOT NULL,
    source_items   INT          NOT NULL DEFAULT 0,
    source_tokens  INT          NOT NULL DEFAULT 0,
    summary_tokens INT          NOT NULL DEFAULT 0,
    embedding_json TEXT,
    expires_at     TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mem_episodic_dev ON memory_episodic (developer_id);
CREATE INDEX idx_mem_episodic_exp ON memory_episodic (expires_at);

-- Tier 3: the semantic experience graph (skills / lessons / facts).
CREATE TABLE memory_experience_nodes (
    id             BIGSERIAL PRIMARY KEY,
    developer_id   VARCHAR(64)  NOT NULL,
    kind           VARCHAR(32)  NOT NULL,   -- EXPERIENCE / FACT / SKILL
    text           TEXT         NOT NULL,
    embedding_json TEXT,
    utility_score  DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    uses           BIGINT       NOT NULL DEFAULT 0,
    last_used_at   TIMESTAMPTZ,
    expires_at     TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mem_nodes_dev ON memory_experience_nodes (developer_id);
CREATE INDEX idx_mem_nodes_exp ON memory_experience_nodes (expires_at);

CREATE TABLE memory_experience_edges (
    id            BIGSERIAL PRIMARY KEY,
    developer_id  VARCHAR(64) NOT NULL,
    from_node_id  BIGINT      NOT NULL REFERENCES memory_experience_nodes (id) ON DELETE CASCADE,
    to_node_id    BIGINT      NOT NULL REFERENCES memory_experience_nodes (id) ON DELETE CASCADE,
    relation      VARCHAR(32) NOT NULL DEFAULT 'SIMILAR',
    weight        DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mem_edges_dev ON memory_experience_edges (developer_id);
CREATE INDEX idx_mem_edges_from ON memory_experience_edges (from_node_id);

-- Tier 4: cold archive (heavily compressed spans, long TTL).
CREATE TABLE memory_archives (
    id            BIGSERIAL PRIMARY KEY,
    developer_id  VARCHAR(64) NOT NULL,
    span_label    VARCHAR(200),
    archive_text  TEXT        NOT NULL,
    item_count    INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mem_archives_dev ON memory_archives (developer_id);

-- Digital-twin counterfactual simulations (offline policy replays).
CREATE TABLE god_mode_simulations (
    id                    BIGSERIAL PRIMARY KEY,
    developer_id          VARCHAR(64) NOT NULL,
    candidate_bundle_id   BIGINT,
    scenario              VARCHAR(64) NOT NULL DEFAULT 'HISTORICAL_REPLAY',
    replayed_requests     INT         NOT NULL DEFAULT 0,
    baseline_metrics_json TEXT,
    candidate_metrics_json TEXT,
    verdict               VARCHAR(16) NOT NULL,
    reason                TEXT,
    confidence            DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_gm_sim_dev ON god_mode_simulations (developer_id);

-- Memory-as-Action audit trail (every autonomous memory decision).
CREATE TABLE god_mode_actions (
    id            BIGSERIAL PRIMARY KEY,
    developer_id  VARCHAR(64) NOT NULL,
    action        VARCHAR(32) NOT NULL,   -- STORE / SUMMARIZE / PROMOTE / PRUNE / ARCHIVE / RETRIEVE / DEFER
    tier          VARCHAR(16),
    detail        TEXT,
    reward        DOUBLE PRECISION,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_gm_actions_dev ON god_mode_actions (developer_id, created_at);
