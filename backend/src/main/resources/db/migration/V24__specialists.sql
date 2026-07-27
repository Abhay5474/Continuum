-- Specialists: a configured model on a connection, plus what a probe learned
-- about it.
--
-- The probe is the honest version of "automatic configuration". You cannot
-- reliably know a model's response shape from a catalogue entry — Roboflow is
-- uniform, Hugging Face varies by task, a custom endpoint is whatever its author
-- chose. Sending one real request and reading the answer is how you find out,
-- and storing that answer means the console can show the developer exactly what
-- their model returns rather than describing it.

CREATE TABLE IF NOT EXISTS specialist (
    id             BIGSERIAL PRIMARY KEY,
    developer_id   VARCHAR(64)      NOT NULL,
    connection_id  BIGINT           NOT NULL,
    -- The developer's name for it, and the identifier the tool is exposed under.
    name           VARCHAR(120)     NOT NULL,
    -- Provider-specific path: "animal-injury/3" for Roboflow, "/predict" for a
    -- custom endpoint.
    model_path     VARCHAR(300)     NOT NULL,
    -- image | text | json | audio
    input_kind     VARCHAR(16)      NOT NULL DEFAULT 'image',
    -- Findings below this are discarded before the model ever sees them.
    min_confidence DOUBLE PRECISION NOT NULL DEFAULT 0.30,
    timeout_seconds INTEGER         NOT NULL DEFAULT 20,
    -- DRAFT until a probe has succeeded. A specialist nobody has proved works
    -- would fail in the middle of a real request instead of at configuration time.
    status         VARCHAR(16)      NOT NULL DEFAULT 'DRAFT',
    -- What the probe saw: the raw response, and the findings parsed from it.
    probe_response TEXT,
    probe_findings TEXT,
    probe_status   INTEGER,
    probe_ms       BIGINT,
    probe_error    TEXT,
    probed_at      TIMESTAMPTZ,
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_specialist_name UNIQUE (developer_id, name)
);

CREATE INDEX IF NOT EXISTS idx_specialist_dev ON specialist (developer_id);

-- Continuum Trace: the visible chain behind an answer.
--
-- Recorded from the start rather than retrofitted, because the value of the
-- specialist layer is largely invisible otherwise — an application that sends an
-- image and receives advice has no way to know a second model was consulted, its
-- findings were structured, and the confidence was checked. These rows are what
-- an external app renders to show its user what happened.
CREATE TABLE IF NOT EXISTS trace_step (
    id           BIGSERIAL PRIMARY KEY,
    trace_id     VARCHAR(64)  NOT NULL,
    developer_id VARCHAR(64)  NOT NULL,
    ordinal      INTEGER      NOT NULL,
    -- INPUT | SPECIALIST | ENRICHMENT | MODEL | VERIFY | OUTPUT
    kind         VARCHAR(16)  NOT NULL,
    label        VARCHAR(200) NOT NULL,
    detail       TEXT,
    status       VARCHAR(16)  NOT NULL DEFAULT 'OK',
    confidence   DOUBLE PRECISION,
    cost         DOUBLE PRECISION NOT NULL DEFAULT 0,
    latency_ms   BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trace_step_trace ON trace_step (trace_id, ordinal);
CREATE INDEX IF NOT EXISTS idx_trace_step_dev ON trace_step (developer_id, created_at DESC);
