-- 实况照片（iOS 成对 Live Photo / Android 单文件 Motion Photo）识别与配对。
--
-- 语义见 src/live_photo.rs：
--   live_role = 'still'  该静态帧有动态部分；live_partner_id 为空表示动态部分尚未入库（半态）
--   live_role = 'motion' 动态部分；配对完成后媒体清单投影不再把它当独立媒体项
--   live_role = 'none'   普通媒体
-- live_probe_version 用于识别逻辑升级后让已索引行在下一次扫描重新探测一次
-- （与 media_assets.time_version 同一手法）；live_probe_state = 'failed' 可重试。

ALTER TABLE media_assets ADD COLUMN live_role TEXT NOT NULL DEFAULT 'none';
ALTER TABLE media_assets ADD COLUMN live_embedded INTEGER NOT NULL DEFAULT 0;
ALTER TABLE media_assets ADD COLUMN live_group_key TEXT;
ALTER TABLE media_assets ADD COLUMN live_partner_id TEXT REFERENCES media_assets(id);
ALTER TABLE media_assets ADD COLUMN live_partner_hash TEXT;
ALTER TABLE media_assets ADD COLUMN live_motion_duration_ms INTEGER;
ALTER TABLE media_assets ADD COLUMN live_probe_state TEXT NOT NULL DEFAULT 'pending';
ALTER TABLE media_assets ADD COLUMN live_probe_version INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS media_assets_live_group_idx
    ON media_assets (live_group_key) WHERE live_group_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS media_assets_live_probe_idx
    ON media_assets (is_video, live_probe_version) WHERE live_role = 'none';
