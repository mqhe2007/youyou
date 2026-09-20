package com.example.youyou_album.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 备份状态派生需要按内容哈希比对本地行与服务器投影行（PRD FR-3）。
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_photos_table_content_hash` ON `photos_table` (`content_hash`)"
        )
    }
}
