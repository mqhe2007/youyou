CREATE TABLE backups_v17 (
    id TEXT PRIMARY KEY NOT NULL,
    job_id TEXT NOT NULL UNIQUE REFERENCES jobs(id),
    status TEXT NOT NULL CHECK (
        status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')
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

INSERT INTO backups_v17
    (id, job_id, status, path, format_version, schema_version, server_version,
     size_bytes, sha256, created_at, updated_at, completed_at, last_error)
SELECT id, job_id, status, path, format_version, schema_version, server_version,
       size_bytes, sha256, created_at, updated_at, completed_at, last_error
FROM backups;

DROP TABLE backups;
ALTER TABLE backups_v17 RENAME TO backups;

CREATE INDEX IF NOT EXISTS backups_created_idx
    ON backups (created_at DESC, id DESC);
