-- Wires the context layer into the chat gateway, behind a per-developer toggle.
--
-- OFF by default, like every other behaviour-changing feature in this console.
-- While false the gateway path is byte-for-byte what it was before: no
-- detection runs, no message is rewritten, nothing is recorded.
--
-- Additive: one nullable-with-default column on an existing table. An existing
-- row gets FALSE, so no deployment changes behaviour by being upgraded.
ALTER TABLE developer_auth
    ADD COLUMN IF NOT EXISTS context_transform_enabled BOOLEAN NOT NULL DEFAULT FALSE;
