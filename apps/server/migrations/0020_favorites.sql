-- 收藏服务端化（PRD FR-6）：收藏是媒体资产的属性，随资产归属用户隔离；
-- 通过媒体 upsert 变更传播到各端，客户端可离线缓存。
ALTER TABLE media_assets ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0;

-- 收藏浏览按用户过滤的主查询索引
CREATE INDEX IF NOT EXISTS media_assets_owner_favorite_idx
    ON media_assets (owner_user_id, is_favorite);
