-- Semantic cache.
--
-- The Command Centre has always listed a semantic cache as part of the system
-- topology, reported as "not installed" because it genuinely was not. This adds
-- it: a per-tenant store of previous gateway answers, matched by meaning rather
-- than by exact string, so a repeated question is answered without paying a
-- provider for it again.
--
-- Everything is scoped to developer_id. One tenant must never be served another
-- tenant's cached answer.

CREATE TABLE IF NOT EXISTS semantic_cache_setting (
    developer_id          VARCHAR(64) PRIMARY KEY,
    enabled               BOOLEAN          NOT NULL DEFAULT FALSE,
    -- Cosine similarity a candidate must reach to be served. Deliberately high
    -- by default: a wrong hit is worse than a miss, because the caller cannot
    -- tell it happened.
    similarity_threshold  DOUBLE PRECISION NOT NULL DEFAULT 0.92,
    ttl_seconds           INTEGER          NOT NULL DEFAULT 86400,
    hits                  BIGINT           NOT NULL DEFAULT 0,
    misses                BIGINT           NOT NULL DEFAULT 0,
    tokens_saved          BIGINT           NOT NULL DEFAULT 0,
    cost_saved            DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at            TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS semantic_cache_entry (
    id            BIGSERIAL PRIMARY KEY,
    developer_id  VARCHAR(64)      NOT NULL,
    -- Exact-match fast path, before any similarity work is done.
    prompt_hash   VARCHAR(64)      NOT NULL,
    prompt_text   TEXT             NOT NULL,
    model         VARCHAR(128),
    response      TEXT             NOT NULL,
    provider      VARCHAR(64),
    tokens        INTEGER          NOT NULL DEFAULT 0,
    cost          DOUBLE PRECISION NOT NULL DEFAULT 0,
    hit_count     INTEGER          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ      NOT NULL,
    last_hit_at   TIMESTAMPTZ
);

-- The similarity scan reads a tenant's most recent live entries, so it is
-- served entirely by this index.
CREATE INDEX IF NOT EXISTS idx_cache_entry_lookup
    ON semantic_cache_entry (developer_id, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_cache_entry_hash
    ON semantic_cache_entry (developer_id, prompt_hash);
