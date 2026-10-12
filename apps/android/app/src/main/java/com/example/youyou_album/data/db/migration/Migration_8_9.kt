package com.example.youyou_album.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1) 标签携带服务端版本：跨端删除/改名的乐观并发（If-Match）需要它。
 *    既有行回填 0，等下一次同步的 upsert/应从服务端补齐。
 * 2) 下载断点续传记录表：记录 MediaStore pending 行与期望校验信息，
 *    重试时按已写入字节用 Range 续传。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tags_table ADD COLUMN server_version INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tags_table ADD COLUMN server_remote_id TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_downloads (
                server_media_id TEXT NOT NULL PRIMARY KEY,
                uri TEXT NOT NULL,
                content_hash TEXT,
                expected_size INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                is_video INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
