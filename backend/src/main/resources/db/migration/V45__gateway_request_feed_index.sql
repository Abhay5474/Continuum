-- The request feed is "this developer's latest N requests". With only an index
-- on developer_id, Postgres read and sorted every request the developer had ever
-- made to return the newest few; gateway_requests gains a row per call, so the
-- console's feed got slower for exactly the customers who used it most.
CREATE INDEX IF NOT EXISTS idx_gwreq_dev_created ON gateway_requests (developer_id, created_at DESC);
