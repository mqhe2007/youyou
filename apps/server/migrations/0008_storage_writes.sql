CREATE TABLE IF NOT EXISTS storage_writes (
    id TEXT PRIMARY KEY NOT NULL,
    operation_id TEXT NOT NULL UNIQUE,
    upload_id TEXT NOT NULL REFERENCES uploads(id),
    storage_id TEXT NOT NULL REFERENCES storages(id),
    temp_path TEXT NOT NULL,
    target_path TEXT NOT NULL,
    expected_sha256 TEXT NOT NULL,
    expected_size INTEGER NOT NULL CHECK (expected_size >= 0),
    state TEXT NOT NULL CHECK (
        state IN ('staged', 'writing', 'written', 'indexed', 'needs_reconcile', 'failed')
    ),
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    last_error TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS storage_writes_recovery_idx
    ON storage_writes (state, updated_at);
