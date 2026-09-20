CREATE TABLE IF NOT EXISTS sync_snapshots (
    id TEXT PRIMARY KEY NOT NULL,
    job_id TEXT NOT NULL REFERENCES jobs(id),
    state TEXT NOT NULL CHECK (state IN ('preparing', 'ready', 'failed', 'expired')),
    snapshot_revision INTEGER,
    changes_cursor TEXT,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_error TEXT
);

CREATE TABLE IF NOT EXISTS sync_snapshot_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id TEXT NOT NULL REFERENCES sync_snapshots(id) ON DELETE CASCADE,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('media', 'albums', 'tags', 'relations')),
    entity_id TEXT NOT NULL,
    entity_version INTEGER NOT NULL,
    sort_at INTEGER,
    payload TEXT NOT NULL,
    UNIQUE (snapshot_id, entity_type, entity_id)
);

CREATE INDEX IF NOT EXISTS sync_snapshot_items_page_idx
    ON sync_snapshot_items (snapshot_id, entity_type, sort_at DESC, entity_id DESC);
