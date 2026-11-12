-- Supports compact durable-state counts without touching large result JSONB documents.
CREATE INDEX jobs_status_idx ON jobs (status);
