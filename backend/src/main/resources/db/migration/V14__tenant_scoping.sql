-- Tenant columns for tables that were engine-wide.
--
-- These rows are per-request observability (which provider a call chose, what a
-- replay verification concluded, which AI fault was injected). Reading them was
-- unscoped, so any signed-in developer saw every tenant's activity. Nullable so
-- existing rows keep working; a null means "recorded before scoping existed" and
-- is only visible to the operator.

ALTER TABLE replay_verification_reports ADD COLUMN IF NOT EXISTS developer_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_rvr_developer ON replay_verification_reports (developer_id, created_at DESC);

ALTER TABLE routing_decisions ADD COLUMN IF NOT EXISTS developer_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_routing_developer ON routing_decisions (developer_id, created_at DESC);

ALTER TABLE ai_chaos_events ADD COLUMN IF NOT EXISTS developer_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_aichaos_developer ON ai_chaos_events (developer_id, created_at DESC);

-- Backfill from the workflow that produced the row, so history that predates
-- these columns becomes visible to the tenant it actually belongs to.
UPDATE replay_verification_reports r
   SET developer_id = w.developer_id
  FROM workflow_instances w
  WHERE r.workflow_id = w.workflow_id AND r.developer_id IS NULL AND w.developer_id IS NOT NULL;

UPDATE routing_decisions d
   SET developer_id = w.developer_id
  FROM workflow_instances w
  WHERE d.workflow_id = w.workflow_id AND d.developer_id IS NULL AND w.developer_id IS NOT NULL;

UPDATE ai_chaos_events e
   SET developer_id = w.developer_id
  FROM workflow_instances w
  WHERE e.workflow_id = w.workflow_id AND e.developer_id IS NULL AND w.developer_id IS NOT NULL;
