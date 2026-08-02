-- The context layer's observability record.
--
-- Additive: no existing table is touched, and nothing outside the new
-- io.continuum.context package reads or writes this. A deployment that never
-- calls a transformer simply leaves it empty.
--
-- Deliberately not storing the input or the rendering. A transformed
-- spreadsheet is the developer's data, often commercially sensitive, and
-- keeping it here would turn an observability table into a second copy of
-- everything they ever sent. Counts and shape are enough to answer "is this
-- helping".
CREATE TABLE IF NOT EXISTS context_transform (
    id              BIGSERIAL PRIMARY KEY,
    developer_id    VARCHAR(64)  NOT NULL,
    context_type    VARCHAR(32)  NOT NULL,
    transformer     VARCHAR(64),
    source_name     VARCHAR(255),
    input_bytes     INTEGER      NOT NULL DEFAULT 0,
    tokens_before   INTEGER      NOT NULL DEFAULT 0,
    tokens_after    INTEGER      NOT NULL DEFAULT 0,
    ambiguities     INTEGER      NOT NULL DEFAULT 0,
    structure       VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_context_transform_developer
    ON context_transform (developer_id, created_at DESC);
