-- Adaptive consensus: stop sampling as soon as the answer is statistically
-- decided, instead of always drawing the configured number.
--
-- OFF for every tenant, existing and new. With it off, confidence measurement
-- draws exactly the configured k, as it always has.
--
-- The threshold is the probability that further sampling would overturn the
-- leading answer. Below it, more samples cannot reasonably change anything and
-- buying them buys nothing.

ALTER TABLE uncertainty_setting
    ADD COLUMN IF NOT EXISTS adaptive_enabled  BOOLEAN          NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS overturn_threshold DOUBLE PRECISION NOT NULL DEFAULT 0.05;
