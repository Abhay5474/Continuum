-- Specialist connections: a developer's credentials for a third-party model
-- provider (Roboflow, Hugging Face, their own endpoint).
--
-- Separate from `provider_credentials`, which holds LLM provider keys keyed by
-- (developer, provider). That shape cannot express two Roboflow workspaces on
-- one account, and it has no room for the endpoint and auth style a specialist
-- needs. The secret itself still lives in the existing vault, encrypted with the
-- same AES-GCM cipher — this table holds everything ABOUT the connection and a
-- reference to the secret, never the secret.

CREATE TABLE IF NOT EXISTS specialist_connection (
    id             BIGSERIAL PRIMARY KEY,
    developer_id   VARCHAR(64)  NOT NULL,
    -- The developer's own name for it: "Roboflow - vet clinic workspace".
    name           VARCHAR(120) NOT NULL,
    -- Which adapter drives it: roboflow | huggingface | http
    provider       VARCHAR(40)  NOT NULL,
    -- Base URL. Fixed for known providers, developer-supplied for `http`.
    base_url       VARCHAR(500),
    -- How the credential is presented: HEADER | QUERY | BEARER | NONE
    auth_style     VARCHAR(16)  NOT NULL DEFAULT 'BEARER',
    -- Header or query-parameter name, when the style needs one.
    auth_param     VARCHAR(80),
    -- Key into the credential vault. The secret is never stored here.
    credential_ref VARCHAR(120),
    -- UNVERIFIED until a test call has succeeded. A connection nobody has
    -- proved works is worse than none, because it fails at request time.
    status         VARCHAR(16)  NOT NULL DEFAULT 'UNVERIFIED',
    last_error     TEXT,
    verified_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_specialist_connection UNIQUE (developer_id, name)
);

CREATE INDEX IF NOT EXISTS idx_specialist_connection_dev
    ON specialist_connection (developer_id);
