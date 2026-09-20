package com.example.youyou_album.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE timeline_times ADD COLUMN sortKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("""
            UPDATE timeline_times SET sortKey=COALESCE(
              (SELECT p.id FROM photos_table p WHERE p.content_hash=timeline_times.hash AND p.source_type!='server' ORDER BY p.id LIMIT 1),
              (SELECT p.id FROM photos_table p JOIN server_media_projection sp ON sp.local_photo_id=p.id WHERE p.content_hash=timeline_times.hash AND sp.server_namespace=timeline_times.namespace ORDER BY p.id LIMIT 1),
              hash)
        """.trimIndent())
    }
}
