CREATE TABLE IF NOT EXISTS idempotency_records (
    principal_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    status_code INTEGER NOT NULL CHECK (status_code >= 0 AND status_code <= 599),
    response_json TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (principal_id, operation, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idempotency_records_updated_idx
    ON idempotency_records (updated_at);
