-- Who may operate the engine. Additive: the admin token keeps working; this is
-- the account-bound path that does not depend on a secret in server config.
CREATE TABLE operator_grants (
    developer_id VARCHAR(64) PRIMARY KEY REFERENCES developers(id) ON DELETE CASCADE,
    granted_by   VARCHAR(64) NULL,
    granted_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
