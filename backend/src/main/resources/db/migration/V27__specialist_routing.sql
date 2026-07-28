-- Specialist routing: which specialists run, rather than all of them, always.
--
-- Off by default and off for every pipeline that already exists. With it off,
-- every step runs regardless of its condition — exactly what pipelines did
-- before this migration — so turning it on is the only thing that changes
-- behaviour, and that is a decision rather than an upgrade side effect.
--
-- The steps column is unchanged. It already holds JSON, and it now holds either
-- the old shape (a bare array of specialist ids) or the new one (objects with a
-- condition). The reader accepts both, so no pipeline needs rewriting and a
-- rollback does not strand data in a shape the previous build cannot parse.

ALTER TABLE pipeline
    ADD COLUMN IF NOT EXISTS routing_enabled BOOLEAN NOT NULL DEFAULT FALSE;
