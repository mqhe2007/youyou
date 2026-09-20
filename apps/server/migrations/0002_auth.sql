CREATE TABLE IF NOT EXISTS admin_users (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS admin_sessions (
    id TEXT PRIMARY KEY NOT NULL,
    session_digest TEXT NOT NULL UNIQUE,
    csrf_digest TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    revoked_at INTEGER
);

CREATE TABLE IF NOT EXISTS pairing_codes (
    id TEXT PRIMARY KEY NOT NULL,
    code_digest TEXT NOT NULL UNIQUE,
    expires_at INTEGER NOT NULL,
    max_attempts INTEGER NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at INTEGER NOT NULL,
    used_at INTEGER
);

CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    token_digest TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER,
    revoked_at INTEGER
);
