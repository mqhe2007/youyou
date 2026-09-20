CREATE TABLE IF NOT EXISTS server_identity (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    instance_id TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL
);

INSERT OR IGNORE INTO server_identity (id, instance_id, created_at)
VALUES (1, lower(hex(randomblob(16))), unixepoch() * 1000);
