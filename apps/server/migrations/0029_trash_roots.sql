-- 回收站跟随库目录（GitHub issue #2）：库目录单独挂载时回收站落在库内，
-- 与照片同挂载卷可原子 rename。trash_root_rel 标记条目所属回收站根。
--
--  * ''          历史行为，路径相对 <media_root>/.youyou-trash
--  * '<library>' 库内回收站，路径相对 <media_root>/<library>/.youyou-trash
--
-- trash_path 语义不变（相对所属回收站根）。唯一索引改为
-- (storage_id, trash_root_rel, trash_path)，避免两个库内同名占位冲突。

ALTER TABLE trash_entries ADD COLUMN trash_root_rel TEXT NOT NULL DEFAULT '';

DROP INDEX IF EXISTS trash_entries_trash_path_idx;

CREATE UNIQUE INDEX IF NOT EXISTS trash_entries_root_path_idx
    ON trash_entries (storage_id, trash_root_rel, trash_path);
