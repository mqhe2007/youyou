-- 历史媒体时间回填按资产探测位置（EXISTS ... media_asset_id）并为每个资产取
-- 一个位置发变更事件（JOIN ... ORDER BY normalized_path LIMIT 1）；media_locations
-- 上唯一以 media_asset_id 前导的索引是 0021 的部分索引（WHERE hash_state='verified'），
-- 缺少 hash_state 谓词的这两处查询吃不到它，逐行退化为全表扫（10 万媒体实测约 2.3s/批）。
-- 本索引非部分、覆盖全部 hash_state，同时让 emit 按序取首行不再需要临时排序。
CREATE INDEX IF NOT EXISTS media_locations_asset_path_idx
    ON media_locations (media_asset_id, normalized_path);
