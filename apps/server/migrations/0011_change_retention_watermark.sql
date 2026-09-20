CREATE TABLE IF NOT EXISTS change_log_meta (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    pruned_through_revision INTEGER NOT NULL DEFAULT 0,
    pruned_through_event_id TEXT NOT NULL DEFAULT ''
);

INSERT OR IGNORE INTO change_log_meta
    (id, pruned_through_revision, pruned_through_event_id)
VALUES (1, 0, '');
