package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本机异步删除（系统回收站/授权）的进行中批次（需求 UoN5J--JHK_R §3）。
 *
 * `createTrashRequest` 只能由 Activity 发起且回调可能在进程重建后丢失，因此把
 * 已提交的 URI 批次落库：重启后核对真实状态，能确认已生效的就收尾，仍存在的
 * 则清除占位，绝不把丢失回调当成失败而重复执行破坏性操作。
 */
@Entity(
    tableName = "pending_local_deletions",
    indices = [Index("batch_id")],
)
data class PendingLocalDeletionEntity(
    @PrimaryKey
    @ColumnInfo(name = "photo_id")
    val photoId: String,

    @ColumnInfo(name = "source_uri")
    val sourceUri: String,

    @ColumnInfo(name = "batch_id")
    val batchId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
