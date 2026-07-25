-- ---------------------------------------------------------------------------
-- V12 — workflow tenancy.
--
-- workflow_instances had no owner, so the console listed every workflow in the
-- engine to every visitor. Adding a nullable owner lets the dashboard show only
-- the signed-in developer's workflows. Nullable on purpose: rows created before
-- this migration (and internal/system workflows) have no owner and are visible
-- only to an operator session, so nothing is lost and nothing leaks.
-- ---------------------------------------------------------------------------
ALTER TABLE workflow_instances ADD COLUMN IF NOT EXISTS developer_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_instances_developer
    ON workflow_instances (developer_id, created_at DESC);
