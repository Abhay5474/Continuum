-- Working-set context assembly: keep what the current request needs, instead of
-- keeping whatever was said most recently.
--
-- OFF for every tenant, existing and new. With it off, eviction is positional
-- exactly as before — oldest paged out first — so turning it on is the only
-- thing that changes which parts of a conversation the model can see.

CREATE TABLE IF NOT EXISTS mmu_setting (
    developer_id      VARCHAR(64) PRIMARY KEY,
    working_set       BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
