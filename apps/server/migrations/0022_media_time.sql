ALTER TABLE media_assets ADD COLUMN sort_source TEXT NOT NULL DEFAULT 'unknown';
ALTER TABLE media_assets ADD COLUMN time_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE media_assets ADD COLUMN original_name TEXT;
CREATE INDEX media_time_backfill ON media_assets(time_version, id);
