CREATE TABLE IF NOT EXISTS albums (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    cover_media_id TEXT REFERENCES media_assets(id),
    version INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER
);

CREATE INDEX IF NOT EXISTS albums_active_sort_idx
    ON albums (deleted_at, updated_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS tags (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS tags_active_name_idx
    ON tags (name)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS album_media (
    album_id TEXT NOT NULL REFERENCES albums(id),
    media_asset_id TEXT NOT NULL REFERENCES media_assets(id),
    version INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (album_id, media_asset_id)
);

CREATE TABLE IF NOT EXISTS media_tags (
    tag_id TEXT NOT NULL REFERENCES tags(id),
    media_asset_id TEXT NOT NULL REFERENCES media_assets(id),
    version INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (tag_id, media_asset_id)
);

CREATE INDEX IF NOT EXISTS album_media_media_idx
    ON album_media (media_asset_id, album_id);

CREATE INDEX IF NOT EXISTS media_tags_media_idx
    ON media_tags (media_asset_id, tag_id);
