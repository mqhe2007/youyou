ALTER TABLE idempotency_records ADD COLUMN request_hash TEXT;
ALTER TABLE idempotency_records ADD COLUMN claim_token TEXT;
