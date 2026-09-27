-- What a firewall event was about, with every secret replaced by a
-- placeholder: "my debit card pin is [REDACTED_CREDENTIAL]". The console showed
-- a category and a count and nothing a person could act on.
ALTER TABLE firewall_events ADD COLUMN IF NOT EXISTS excerpt TEXT;
