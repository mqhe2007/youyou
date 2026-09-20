package com.example.youyou_album.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.youyou_album.domain.model.MediaTime

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE photos_table ADD COLUMN taken_at INTEGER")
        db.execSQL("ALTER TABLE photos_table ADD COLUMN sort_source TEXT NOT NULL DEFAULT 'unknown'")
        db.execSQL("ALTER TABLE photos_table ADD COLUMN time_version INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE photos_table ADD COLUMN original_name TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS timeline_times (namespace TEXT NOT NULL, hash TEXT NOT NULL, at INTEGER, source TEXT NOT NULL, PRIMARY KEY(namespace, hash))")
        // Bounded keyset batches; old created_at is DATE_ADDED, never EXIF.
        var after = ""
        while (true) {
            val rows = mutableListOf<Triple<String, String, Long?>>()
            db.query("SELECT id, name, sort_at, source_type, path FROM photos_table WHERE id > ? ORDER BY id LIMIT 256", arrayOf(after)).use { c ->
                while (c.moveToNext()) {
                    // Old uploads/downloads did not retain the source filename. Empty explicitly means unknown.
                    val generatedName = (c.getString(3) == "server" && c.getString(4).contains("/uploads/")) || c.getString(4).contains("/Pictures/youyou/")
                    rows.add(Triple(c.getString(0), if (generatedName) "" else c.getString(1), if (c.isNull(2)) null else c.getLong(2)))
                }
            }
            if (rows.isEmpty()) break
            for ((id, name, old) in rows) {
                val time = MediaTime.resolve(name, null, null, old)
                db.execSQL("UPDATE photos_table SET sort_at=?, sort_source=?, original_name=? WHERE id=?", arrayOf<Any?>(time.at, time.source, name, id))
            }
            after = rows.last().first
        }
        db.execSQL("""
            INSERT OR REPLACE INTO timeline_times(namespace,hash,at,source)
            SELECT COALESCE((SELECT server_namespace FROM server_sync_state WHERE id=1),''),p.content_hash,p.sort_at,p.sort_source
            FROM photos_table p WHERE p.content_hash IS NOT NULL AND p.sort_at IS NOT NULL AND
              (p.source_type!='server' OR EXISTS(SELECT 1 FROM server_media_projection sp JOIN server_sync_state ss ON ss.server_namespace=sp.server_namespace WHERE sp.local_photo_id=p.id AND ss.id=1))
            ORDER BY CASE p.sort_source WHEN 'filename' THEN 3 WHEN 'added' THEN 2 WHEN 'modified' THEN 1 ELSE 0 END ASC,p.sort_at DESC,p.id DESC
        """.trimIndent())
        // Historical application downloads can recover the source time from their verified remote twin.
        db.execSQL("""
            UPDATE photos_table SET
              sort_at=(SELECT at FROM timeline_times t WHERE t.hash=photos_table.content_hash AND t.namespace=COALESCE((SELECT server_namespace FROM server_sync_state WHERE id=1),'')),
              sort_source=(SELECT source FROM timeline_times t WHERE t.hash=photos_table.content_hash AND t.namespace=COALESCE((SELECT server_namespace FROM server_sync_state WHERE id=1),''))
            WHERE source_type='local' AND path LIKE '%/Pictures/youyou/%' AND EXISTS(
              SELECT 1 FROM timeline_times t WHERE t.hash=photos_table.content_hash AND t.namespace=COALESCE((SELECT server_namespace FROM server_sync_state WHERE id=1),''))
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_table_source_uri ON photos_table(source_uri)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_table_sort_at ON photos_table(sort_at)")
    }
}
