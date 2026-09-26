-- A run's outbox messages are read by run (the run detail page, and an account's
-- delivery count), which had no index and scanned the whole table each time.
create index if not exists idx_outbox_workflow on outbox (workflow_id);
