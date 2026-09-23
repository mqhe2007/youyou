package com.example.youyou_album.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 实况照片（iOS Live Photo / Android Motion Photo）标记（需求 QI-8Xs1ejZaQ）。
 *
 * 服务端投影为权威：
 *  * `live_role` 为 `null`/`none` 表示普通媒体；
 *  * `still` 表示静态帧，`live_partner_id` 为空即「动态部分尚未入库」的半态；
 *  * `motion` 表示该行只是某段实况的动态部分，不作为独立媒体项展示。
 *
 * 既有行不回填：实况语义随下一次同步/扫描投影写入，且服务端清单本就不包含
 * `motion` 行。单文件动态照片的内嵌部分标记在静态帧上（`live_embedded`）。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE photos_table ADD COLUMN live_role TEXT")
        db.execSQL("ALTER TABLE photos_table ADD COLUMN live_embedded INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE photos_table ADD COLUMN live_group_key TEXT")
        db.execSQL("ALTER TABLE photos_table ADD COLUMN live_partner_id TEXT")
        db.execSQL("ALTER TABLE photos_table ADD COLUMN live_partner_hash TEXT")
        db.execSQL("ALTER TABLE photos_table ADD COLUMN live_motion_duration_ms INTEGER")
    }
}
