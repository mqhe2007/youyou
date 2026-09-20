CREATE TABLE IF NOT EXISTS change_revision (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    revision INTEGER NOT NULL CHECK (revision >= 0)
);

INSERT OR IGNORE INTO change_revision (id, revision)
SELECT 1, COALESCE(MAX(revision), 0) FROM change_log;

CREATE TABLE change_log_v16 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    event_id TEXT NOT NULL UNIQUE,
    entity TEXT NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('upsert', 'delete')),
    entity_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

INSERT INTO change_log_v16
    (revision, event_id, entity, operation, entity_id, version, payload, created_at)
SELECT revision, event_id, entity, operation, entity_id, version, payload, created_at
FROM change_log
ORDER BY revision ASC, event_id ASC;

DROP TABLE change_log;
ALTER TABLE change_log_v16 RENAME TO change_log;

CREATE INDEX IF NOT EXISTS change_log_order_idx
    ON change_log (revision ASC, event_id ASC);
