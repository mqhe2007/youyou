CREATE INDEX IF NOT EXISTS audit_log_created_idx
    ON audit_log (created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS backups (
    id TEXT PRIMARY KEY NOT NULL,
    job_id TEXT NOT NULL UNIQUE REFERENCES jobs(id),
    status TEXT NOT NULL CHECK (
        status IN ('queued', 'running', 'succeeded', 'failed')
    ),
    path TEXT,
    format_version INTEGER,
    schema_version INTEGER,
    server_version TEXT,
    size_bytes INTEGER,
    sha256 TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    completed_at INTEGER,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS backups_created_idx
    ON backups (created_at DESC, id DESC);
