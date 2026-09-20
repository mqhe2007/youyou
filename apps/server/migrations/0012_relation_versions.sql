CREATE TABLE IF NOT EXISTS relation_versions (
    entity TEXT NOT NULL CHECK (entity IN ('album_relation', 'tag_relation')),
    relation_id TEXT NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    deleted_at INTEGER,
    PRIMARY KEY (entity, relation_id)
);

INSERT OR IGNORE INTO relation_versions (entity, relation_id, version)
SELECT 'album_relation', album_id || ':' || media_asset_id, version
FROM album_media;

INSERT OR IGNORE INTO relation_versions (entity, relation_id, version)
SELECT 'tag_relation', tag_id || ':' || media_asset_id, version
FROM media_tags;
