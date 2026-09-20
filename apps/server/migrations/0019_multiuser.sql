-- 多用户基座：用户、媒体库目录绑定与数据归属。
-- 服务端从"单管理员 + 全局媒体库"转向"管理员运营 + 多用户各自绑定媒体库"，
-- 媒体/标签/变更流均按用户隔离（见 docs/产品需求.md）。

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS users_name_idx ON users (name);

-- 一个用户绑定一个媒体库目录（v1 一人一库）；一个目录至多被一个用户绑定。
-- root_path 为相对存储根的相对路径，与 media_locations.normalized_path 同一坐标系。
CREATE TABLE IF NOT EXISTS user_libraries (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    root_path TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS user_libraries_root_idx ON user_libraries (root_path);

ALTER TABLE devices ADD COLUMN user_id INTEGER REFERENCES users(id);
ALTER TABLE pairing_codes ADD COLUMN user_id INTEGER REFERENCES users(id);
ALTER TABLE media_assets ADD COLUMN owner_user_id INTEGER REFERENCES users(id);
ALTER TABLE tags ADD COLUMN user_id INTEGER REFERENCES users(id);
ALTER TABLE change_log ADD COLUMN owner_user_id INTEGER REFERENCES users(id);
ALTER TABLE jobs ADD COLUMN user_id INTEGER REFERENCES users(id);
ALTER TABLE sync_snapshots ADD COLUMN user_id INTEGER REFERENCES users(id);

-- 内容去重从全局改为按用户：同一内容在不同用户库中是独立的媒体资产，
-- 否则跨用户的删除/墓碑会相互波及，破坏隔离。
DROP INDEX IF EXISTS media_assets_verified_blob_idx;
CREATE UNIQUE INDEX IF NOT EXISTS media_assets_verified_blob_owner_idx
    ON media_assets (blob_id, owner_user_id)
    WHERE blob_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS media_assets_owner_state_idx
    ON media_assets (owner_user_id, identity_state);

-- 标签名唯一性从全局改为用户内唯一（NULL user_id 的遗留标签互不冲突）。
DROP INDEX IF EXISTS tags_active_name_idx;
CREATE UNIQUE INDEX IF NOT EXISTS tags_active_user_name_idx
    ON tags (user_id, name)
    WHERE deleted_at IS NULL;

-- 变更流按用户过滤的主查询索引。
CREATE INDEX IF NOT EXISTS change_log_owner_revision_idx
    ON change_log (owner_user_id, revision);
