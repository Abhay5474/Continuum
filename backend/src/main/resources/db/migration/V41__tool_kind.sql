-- Tool kind: the first place the Specialist layer's assumption that every tool
-- is a detector is written down rather than implied.
--
-- The layer's data model carried label + confidence + region and nothing else,
-- so OCR, transcription and document extraction were installable and produced
-- no usable output at all. Knowing a tool's kind is what lets the runtime treat
-- unscored evidence as evidence rather than as a failed detection.
--
-- Additive and reversible: dropping the column returns the table to its previous
-- shape, and every existing row keeps working because DETECTION is what they all
-- were.

ALTER TABLE specialist
    ADD COLUMN IF NOT EXISTS tool_kind VARCHAR(24) NOT NULL DEFAULT 'DETECTION';

-- Backfill from what the row already told us. Existing image specialists are
-- detectors; a text one is a classifier. Nothing else can be inferred, and
-- guessing further would relabel a developer's tool behind their back.
UPDATE specialist SET tool_kind = 'CLASSIFICATION' WHERE input_kind = 'text';
UPDATE specialist SET tool_kind = 'DETECTION'      WHERE input_kind = 'image';

CREATE INDEX IF NOT EXISTS idx_specialist_tool_kind ON specialist (developer_id, tool_kind);
