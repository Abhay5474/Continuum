-- Output verification: does the advice match the findings.
--
-- OFF for every pipeline, existing and new. ENFORCE replaces the text a real
-- user reads, so it can only ever be a decision someone made, never something
-- that arrived with an upgrade.
--
-- Three modes rather than a boolean, because MONITOR is the one most people
-- should start on: it records the verdict without touching the answer, so a
-- developer can find out how often their pipeline would have been stopped
-- before they let it stop anything.

ALTER TABLE pipeline
    ADD COLUMN IF NOT EXISTS verification_mode VARCHAR(16) NOT NULL DEFAULT 'OFF';
