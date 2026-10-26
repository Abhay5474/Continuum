-- Applications. An account can run several apps, each calling Continuum with
-- its own API key; until now everything they captured was pooled per account,
-- so one app's memory, cache and experience were recalled into another's
-- answers, and the console could not tell their data apart.
--
-- Additive only: a table, a column on keys, and a nullable app_id on every
-- table of captured data. NULL means "untagged": made in the console, or before
-- this migration. Untagged rows stay visible to every app.

CREATE TABLE IF NOT EXISTS apps (
    id           BIGSERIAL PRIMARY KEY,
    developer_id VARCHAR(64)  NOT NULL,
    name         VARCHAR(120) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_apps_developer ON apps (developer_id);

ALTER TABLE developer_api_keys ADD COLUMN IF NOT EXISTS app_id BIGINT;

-- One app per existing key, named after its label when it has one.
DO $$
DECLARE
    k RECORD;
    new_app BIGINT;
BEGIN
    FOR k IN SELECT id, developer_id, label, key_prefix, created_at
             FROM developer_api_keys WHERE app_id IS NULL ORDER BY id LOOP
        INSERT INTO apps (developer_id, name, created_at)
        VALUES (k.developer_id,
                COALESCE(NULLIF(btrim(k.label), ''), 'App ' || k.key_prefix),
                k.created_at)
        RETURNING id INTO new_app;
        UPDATE developer_api_keys SET app_id = new_app WHERE id = k.id;
    END LOOP;
END $$;

-- The tag, on every table of captured data.
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'routing_decisions', 'mmu_request_metrics', 'routing_strategy_decision',
        'cascade_decision', 'breaker_event', 'compression_metrics',
        'autopilot_request_labels', 'firewall_events', 'replay_verification_reports',
        'loop_event', 'gateway_requests', 'god_mode_actions',
        'degradation_event', 'quality_gate_check', 'ai_chaos_events', 'trace_step',
        'saga_event', 'decision_record', 'autopilot_feedback', 'mmu_stub_events',
        'uncertainty_measurement', 'repair_attempt', 'dag_runs', 'workflow_instances',
        'context_transform', 'memory_experience_nodes', 'memory_experience_edges',
        'memory_working', 'memory_episodic', 'memory_archives', 'semantic_cache_entry',
        'mmu_semantic_stubs', 'memory_entries'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS app_id BIGINT', t);
        -- An account that only ever had one key only ever had one app: its
        -- history is that app's. With several keys there is no telling which
        -- app made which row, so those stay untagged.
        IF t = 'memory_entries' THEN
            -- Program memory carries its account in the scope: "<developer>::<scope>".
            EXECUTE 'UPDATE memory_entries m SET app_id = k.app_id
                     FROM developer_api_keys k
                     WHERE m.app_id IS NULL
                       AND m.scope LIKE k.developer_id || ''::%''
                       AND (SELECT count(*) FROM developer_api_keys o
                            WHERE o.developer_id = k.developer_id) = 1';
        ELSE
            EXECUTE format('UPDATE %I r SET app_id = k.app_id
                            FROM developer_api_keys k
                            WHERE r.app_id IS NULL
                              AND r.developer_id = k.developer_id
                              AND (SELECT count(*) FROM developer_api_keys o
                                   WHERE o.developer_id = k.developer_id) = 1', t);
        END IF;
    END LOOP;
END $$;

-- The request log is what the console reads most, by account and app.
CREATE INDEX IF NOT EXISTS idx_gateway_requests_app ON gateway_requests (developer_id, app_id, created_at DESC);
