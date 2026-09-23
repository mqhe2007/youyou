package com.example.youyou_album.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.youyou_album.data.db.dao.MediaOperationDao
import com.example.youyou_album.data.db.dao.PhotoDao
import com.example.youyou_album.data.db.dao.ScanCursorDao
import com.example.youyou_album.data.db.dao.ServerExportDao
import com.example.youyou_album.data.db.dao.ServerProjectionDao
import com.example.youyou_album.data.db.dao.ServerSyncStateDao
import com.example.youyou_album.data.db.dao.TagDao
import com.example.youyou_album.data.db.dao.TaskDao
import com.example.youyou_album.data.db.entity.PendingLocalDeletionEntity
import com.example.youyou_album.data.db.entity.PendingMediaOperationEntity
import com.example.youyou_album.data.db.entity.PhotoEntity
import com.example.youyou_album.data.db.entity.PhotoTagEntity
import com.example.youyou_album.data.db.entity.ScanCursorEntity
import com.example.youyou_album.data.db.entity.ServerMediaExportEntity
import com.example.youyou_album.data.db.entity.ServerMediaProjectionEntity
import com.example.youyou_album.data.db.entity.ServerSyncStateEntity
import com.example.youyou_album.data.db.entity.TagEntity
import com.example.youyou_album.data.db.entity.TaskEntity

@Database(
    entities = [
        PhotoEntity::class,
        com.example.youyou_album.data.db.entity.TimelineTimeEntity::class,
        TagEntity::class,
        PhotoTagEntity::class,
        TaskEntity::class,
        ScanCursorEntity::class,
        ServerMediaProjectionEntity::class,
        ServerMediaExportEntity::class,
        ServerSyncStateEntity::class,
        PendingMediaOperationEntity::class,
        PendingLocalDeletionEntity::class,
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun tagDao(): TagDao
    abstract fun taskDao(): TaskDao
    abstract fun scanCursorDao(): ScanCursorDao
    abstract fun serverProjectionDao(): ServerProjectionDao
    abstract fun serverExportDao(): ServerExportDao
    abstract fun serverSyncStateDao(): ServerSyncStateDao
    abstract fun mediaOperationDao(): MediaOperationDao

    companion object {
        const val DATABASE_NAME = "youyou_album.db"
    }
}
