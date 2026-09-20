CREATE TABLE IF NOT EXISTS uploads (
    id TEXT PRIMARY KEY NOT NULL,
    operation_id TEXT NOT NULL UNIQUE,
    principal_id TEXT NOT NULL,
    expected_size INTEGER NOT NULL CHECK (expected_size >= 0),
    expected_sha256 TEXT NOT NULL CHECK (length(expected_sha256) = 64),
    mime_type TEXT,
    file_name TEXT NOT NULL,
    part_size INTEGER NOT NULL CHECK (part_size > 0),
    part_count INTEGER NOT NULL CHECK (part_count >= 0),
    status TEXT NOT NULL CHECK (status IN ('active', 'completing', 'completed', 'failed', 'expired')),
    target_path TEXT,
    media_id TEXT,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_error TEXT
);

CREATE TABLE IF NOT EXISTS upload_parts (
    upload_id TEXT NOT NULL REFERENCES uploads(id) ON DELETE CASCADE,
    part_number INTEGER NOT NULL CHECK (part_number >= 0),
    size INTEGER NOT NULL CHECK (size >= 0),
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
    path TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (upload_id, part_number)
);
