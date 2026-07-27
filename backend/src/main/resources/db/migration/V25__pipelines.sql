-- Pipelines: input -> specialist(s) -> structured context -> model -> answer.
--
-- This is what an external application actually calls. It sends an image and
-- receives advice; it never learns that a detector ran, what it was called, or
-- who hosts it.
--
-- Execution is synchronous, deliberately. Compiling to the durable workflow
-- engine would make a pipeline replayable and crash-proof, which is the right
-- shape for a long back-office job and the wrong one for a chat turn — the
-- caller is holding an open connection waiting for an answer. Durable execution
-- of the same definition is a separate entry point rather than a different
-- pipeline model.

CREATE TABLE IF NOT EXISTS pipeline (
    id             BIGSERIAL PRIMARY KEY,
    developer_id   VARCHAR(64)  NOT NULL,
    -- The identifier the external app calls: POST /api/gateway/pipeline/{name}
    name           VARCHAR(120) NOT NULL,
    description    VARCHAR(500),
    -- image | text | json | audio — what the app sends.
    input_kind     VARCHAR(16)  NOT NULL DEFAULT 'image',
    -- Prepended to the model's instructions. This is where a developer says
    -- "you are advising on animal first aid" rather than re-sending it per call.
    system_prompt  TEXT,
    -- Ordered specialist ids. A small ordered list rather than a join table:
    -- pipelines are short, order is the whole meaning, and reordering a join
    -- table is more machinery than the problem deserves.
    steps          TEXT         NOT NULL DEFAULT '[]',
    enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    runs           BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pipeline_name UNIQUE (developer_id, name)
);

CREATE INDEX IF NOT EXISTS idx_pipeline_dev ON pipeline (developer_id);
