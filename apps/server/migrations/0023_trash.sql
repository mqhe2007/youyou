-- 删除原件与两侧回收能力（需求 UoN5J--JHK_R，2026-09-13 第三轮规格）。
--
-- 约定：
--  * 服务端回收站位于全部可扫描用户库树之外，且与媒体源处于同一文件系统；
--    文件移动使用同盘 rename，不覆盖目标，不隐式 copy+delete。
--  * 文件系统与 SQLite 不能共用原子事务，故以 media_operations 持久化操作阶段，
--    崩溃后按阶段与文件系统实际状态做补偿（前滚 / 回滚）。
--  * trash_entries 记录被回收的每个原件（同库多 location 各一行），恢复时按
--    media_id 归组还原；操作结果凭据（media_operations）不随回收条目清空而删除。

CREATE TABLE IF NOT EXISTS trash_entries (
    id TEXT PRIMARY KEY NOT NULL,
    media_id TEXT NOT NULL,
    owner_user_id INTEGER REFERENCES users(id),
    storage_id TEXT NOT NULL,
    original_path TEXT NOT NULL,
    file_name TEXT NOT NULL,
    -- 相对回收站根的路径；唯一索引保证不会覆盖回收站内既有文件。
    trash_path TEXT NOT NULL,
    size INTEGER NOT NULL CHECK (size >= 0),
    blob_id TEXT,
    content_hash TEXT,
    mime_type TEXT,
    is_video INTEGER NOT NULL DEFAULT 0 CHECK (is_video IN (0, 1)),
    duration_ms INTEGER,
    video_codec TEXT,
    width INTEGER,
    height INTEGER,
    taken_at INTEGER,
    sort_at INTEGER,
    sort_source TEXT,
    time_version INTEGER,
    original_name TEXT,
    is_favorite INTEGER NOT NULL DEFAULT 0 CHECK (is_favorite IN (0, 1)),
    asset_version INTEGER NOT NULL,
    deleted_by TEXT NOT NULL,
    operation_id TEXT,
    state TEXT NOT NULL CHECK (state IN ('active', 'restoring', 'restored', 'purged')),
    deleted_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    restored_at INTEGER,
    purged_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS trash_entries_trash_path_idx
    ON trash_entries (trash_path);

CREATE INDEX IF NOT EXISTS trash_entries_state_expires_idx
    ON trash_entries (state, expires_at);

CREATE INDEX IF NOT EXISTS trash_entries_media_state_idx
    ON trash_entries (media_id, state);

-- 标签关系随回收保存；恢复时按 tag_id 重新关联，已删除的标签跳过而不重建。
CREATE TABLE IF NOT EXISTS trash_entry_tags (
    trash_entry_id TEXT NOT NULL REFERENCES trash_entries(id) ON DELETE CASCADE,
    tag_id TEXT NOT NULL,
    tag_name TEXT,
    PRIMARY KEY (trash_entry_id, tag_id)
);

-- 设备/管理端删除、恢复、彻底删除的操作日志：承载幂等结果与崩溃恢复阶段。
-- scope_key 隔离身份（`admin` / `user:<id>`），切换账号不得复用执行。
CREATE TABLE IF NOT EXISTS media_operations (
    id TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('delete', 'restore', 'purge')),
    media_id TEXT NOT NULL,
    owner_user_id INTEGER,
    device_id TEXT,
    expected_version INTEGER,
    state TEXT NOT NULL CHECK (
        state IN ('in_progress', 'succeeded', 'failed', 'conflict', 'not_found')
    ),
    phase TEXT NOT NULL CHECK (phase IN ('initiated', 'files_moved', 'committed')),
    payload TEXT,
    result TEXT,
    error TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    finished_at INTEGER,
    PRIMARY KEY (scope_key, id)
);

CREATE INDEX IF NOT EXISTS media_operations_media_idx
    ON media_operations (media_id);

CREATE INDEX IF NOT EXISTS media_operations_pending_idx
    ON media_operations (finished_at, phase);

-- 扫描守卫：正在执行文件操作的原路径。扫描遇到这些路径时跳过，
-- 进程崩溃后由启动恢复流程清理残留行。
CREATE TABLE IF NOT EXISTS media_pending_paths (
    normalized_path TEXT PRIMARY KEY NOT NULL,
    operation_id TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
