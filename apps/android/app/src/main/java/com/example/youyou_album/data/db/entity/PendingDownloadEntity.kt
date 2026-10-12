package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 「仅远程」媒体下载到系统相册的中断续传记录。
 *
 * 下载直接写入 MediaStore 的 IS_PENDING=1 行；进程死亡或失败时该行与这里的
 * 记录一起保留，下一次重试按已写入字节数用 HTTP Range 断点续传。媒体版本变化
 * （内容哈希/大小不匹配）或下载完成后记录被删除。
 */
@Entity(tableName = "pending_downloads")
data class PendingDownloadEntity(
    @PrimaryKey
    @ColumnInfo(name = "server_media_id")
    val serverMediaId: String,

    /** 系统相册中 pending 行的 Content URI。 */
    val uri: String,

    /** 期望内容哈希（可能未知）；用于校验续传对象未被更换。 */
    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,

    /** 期望字节数；0 表示未知。 */
    @ColumnInfo(name = "expected_size")
    val expectedSize: Long = 0,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "is_video")
    val isVideo: Boolean,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
