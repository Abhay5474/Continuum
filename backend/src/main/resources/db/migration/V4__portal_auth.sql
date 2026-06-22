-- Continuum V4 — two-tier portal authentication.
-- Additive: the existing `developers` table is NOT altered. Portal sign-in
-- credentials and the per-developer routing preference live in a new table.

CREATE TABLE developer_auth (
    developer_id         VARCHAR(64) PRIMARY KEY,
    password_hash        VARCHAR(256) NOT NULL,
    use_own_keys_primary BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Speeds up developer login-by-email and signup uniqueness checks.
CREATE INDEX idx_developers_email ON developers (lower(email));
