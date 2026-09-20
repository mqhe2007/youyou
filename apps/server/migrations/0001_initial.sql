CREATE TABLE IF NOT EXISTS storages (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    root_path TEXT NOT NULL,
    read_only INTEGER NOT NULL DEFAULT 0 CHECK (read_only IN (0, 1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    CHECK (id = 'local')
);

CREATE TABLE IF NOT EXISTS content_blobs (
    id TEXT PRIMARY KEY NOT NULL,
    hash_algorithm TEXT NOT NULL CHECK (hash_algorithm = 'sha256'),
    content_hash TEXT NOT NULL,
    size INTEGER NOT NULL CHECK (size >= 0),
    created_at INTEGER NOT NULL,
    UNIQUE (hash_algorithm, content_hash)
);

CREATE TABLE IF NOT EXISTS media_assets (
    id TEXT PRIMARY KEY NOT NULL,
    blob_id TEXT REFERENCES content_blobs(id),
    identity_state TEXT NOT NULL CHECK (identity_state IN ('pending', 'verified', 'tombstoned')),
    name TEXT NOT NULL,
    mime_type TEXT,
    is_video INTEGER NOT NULL DEFAULT 0 CHECK (is_video IN (0, 1)),
    duration_ms INTEGER,
    width INTEGER,
    height INTEGER,
    taken_at INTEGER,
    sort_at INTEGER,
    version INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS media_assets_verified_blob_idx
    ON media_assets (blob_id)
    WHERE blob_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS media_locations (
    id TEXT PRIMARY KEY NOT NULL,
    media_asset_id TEXT NOT NULL REFERENCES media_assets(id),
    storage_id TEXT NOT NULL REFERENCES storages(id),
    normalized_path TEXT NOT NULL,
    file_name TEXT NOT NULL,
    size INTEGER NOT NULL CHECK (size >= 0),
    modified_at INTEGER,
    hash_state TEXT NOT NULL CHECK (hash_state IN ('pending', 'verified', 'stale', 'failed')),
    observed_size INTEGER NOT NULL CHECK (observed_size >= 0),
    observed_mtime INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (storage_id, normalized_path)
);

CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY NOT NULL,
    kind TEXT NOT NULL,
    status TEXT NOT NULL CHECK (
        status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled', 'interrupted')
    ),
    current INTEGER NOT NULL DEFAULT 0 CHECK (current >= 0),
    total INTEGER,
    message TEXT,
    last_error TEXT,
    checkpoint TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    finished_at INTEGER,
    heartbeat_at INTEGER
);

CREATE TABLE IF NOT EXISTS change_log (
    revision INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE,
    entity TEXT NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('upsert', 'delete')),
    entity_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT NOT NULL,
    result TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
