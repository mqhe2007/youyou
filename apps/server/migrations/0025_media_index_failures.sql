-- 索引失败的持久记录：损坏/不可解码文件的原因与指纹。
-- 用途：①扫描不再对未变更的失败文件重复哈希；②管理端可列出失败清单并重试。
CREATE TABLE IF NOT EXISTS media_index_failures (
    storage_id TEXT NOT NULL REFERENCES storages(id),
    normalized_path TEXT NOT NULL,
    file_name TEXT NOT NULL,
    size INTEGER NOT NULL CHECK (size >= 0),
    modified_at INTEGER,
    reason TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 1 CHECK (attempts >= 1),
    first_failed_at INTEGER NOT NULL,
    last_failed_at INTEGER NOT NULL,
    PRIMARY KEY (storage_id, normalized_path)
);

CREATE INDEX IF NOT EXISTS media_index_failures_recent_idx
    ON media_index_failures (last_failed_at DESC);
