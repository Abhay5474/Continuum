-- Confidence policy: what the model is allowed to do with the evidence it got.
--
-- Off by default, and off for every pipeline that already exists. Turning this
-- on changes what a live endpoint says to real users — a migration that enabled
-- it would rewrite the behaviour of running applications without anyone asking.
--
-- The thresholds default to the same numbers the context builder groups its
-- prose bands by, so "Possible, less certain:" in the prompt and MEDIUM in the
-- policy always mean the same thing. Two sets of thresholds that could drift
-- apart is one set too many.

ALTER TABLE pipeline
    ADD COLUMN IF NOT EXISTS policy_enabled   BOOLEAN          NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS strong_threshold DOUBLE PRECISION NOT NULL DEFAULT 0.70,
    ADD COLUMN IF NOT EXISTS weak_threshold   DOUBLE PRECISION NOT NULL DEFAULT 0.40,
    -- When nothing was found, or nothing ran: ask the model to say so (false),
    -- or refuse without calling a model at all (true). Declining is cheaper and
    -- strictly safer; asking is friendlier and keeps the model able to answer
    -- the parts of a question that need no findings. Neither is right for
    -- everyone, so it is a choice rather than a default buried in code.
    ADD COLUMN IF NOT EXISTS decline_on_no_evidence BOOLEAN    NOT NULL DEFAULT FALSE;
