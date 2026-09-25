-- Links a logged gateway request to its provenance trail (decision_record.request_id),
-- when the tenant has provenance on. Null otherwise, and for requests logged before this.
ALTER TABLE gateway_requests ADD COLUMN trace_id VARCHAR(64) NULL;
