package com.example.youyou_album.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 删除原件与两侧回收能力（需求 UoN5J--JHK_R）。
 *
 * 新增两张表：
 *  * `pending_media_operations`：已发出的远程删除操作的结果查询上下文（幂等/迟到查询）。
 *  * `pending_local_deletions`：本机异步删除（系统回收站/授权）进行中的 URI 批次，
 *    供进程重建后核对真实状态。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_media_operations (
                operation_id TEXT NOT NULL PRIMARY KEY,
                kind TEXT NOT NULL,
                server_namespace TEXT NOT NULL,
                server_media_id TEXT NOT NULL,
                local_photo_id TEXT,
                expected_version INTEGER,
                state TEXT NOT NULL,
                message TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_media_operations_server_namespace ON pending_media_operations(server_namespace)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_media_operations_state ON pending_media_operations(state)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_local_deletions (
                photo_id TEXT NOT NULL PRIMARY KEY,
                source_uri TEXT NOT NULL,
                batch_id TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_local_deletions_batch_id ON pending_local_deletions(batch_id)"
        )
    }
}
