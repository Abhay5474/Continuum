-- How a developer came to be on their plan.
--
-- The plan column alone could not distinguish "paid for it", "the operator
-- granted it" and "asked for it and nobody checked" — and the last one was what
-- actually happened, because a single API call moved an account onto the
-- largest paid tier for free. Recording provenance makes an unpaid upgrade
-- impossible to perform silently and easy to audit.

ALTER TABLE developer_billing ADD COLUMN IF NOT EXISTS plan_source VARCHAR(24) NOT NULL DEFAULT 'FREE_TIER';
ALTER TABLE developer_billing ADD COLUMN IF NOT EXISTS payment_reference VARCHAR(200);
ALTER TABLE developer_billing ADD COLUMN IF NOT EXISTS granted_by VARCHAR(120);
ALTER TABLE developer_billing ADD COLUMN IF NOT EXISTS period_end TIMESTAMPTZ;

-- Anything already sitting on a paid plan predates the check, so it is marked
-- as such rather than being backdated into looking legitimately purchased.
UPDATE developer_billing
   SET plan_source = 'UNVERIFIED_LEGACY'
 WHERE plan <> 'FREE' AND plan_source = 'FREE_TIER';
