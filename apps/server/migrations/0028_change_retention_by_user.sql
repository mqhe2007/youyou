-- 变更流保留策略收敛：
-- 1) created_at 索引：保留期裁剪的边界查询与删除不再全表扫描；
-- 2) 按用户水位：裁剪只迫使「自身变更被裁掉」的用户做全量重同步，
--    不再用全局水位牵连其他用户；
-- 3) 回填历史 media 变更的属主：管理端移动/重命名修复前写入的变更缺少
--    owner_user_id，按资产当前归属补上，让尚未换绑的设备收到路径纠正。

CREATE INDEX IF NOT EXISTS change_log_created_idx ON change_log (created_at);

CREATE TABLE IF NOT EXISTS change_log_user_meta (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    pruned_through_revision INTEGER NOT NULL DEFAULT 0,
    pruned_through_event_id TEXT NOT NULL DEFAULT ''
);

UPDATE change_log
SET owner_user_id = (
    SELECT a.owner_user_id FROM media_assets a
    WHERE a.id = change_log.entity_id AND a.owner_user_id IS NOT NULL
)
WHERE entity = 'media'
  AND owner_user_id IS NULL
  AND EXISTS (
      SELECT 1 FROM media_assets a
      WHERE a.id = change_log.entity_id AND a.owner_user_id IS NOT NULL
  );
