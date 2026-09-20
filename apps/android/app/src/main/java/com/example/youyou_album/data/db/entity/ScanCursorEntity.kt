package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_cursors_table")
data class ScanCursorEntity(
    @PrimaryKey
    val scope: String,

    @ColumnInfo(name = "watermark_ms")
    val watermarkMs: Long? = null,

    @ColumnInfo(name = "last_full_reconcile_at_ms")
    val lastFullReconcileAtMs: Long? = null,
)
