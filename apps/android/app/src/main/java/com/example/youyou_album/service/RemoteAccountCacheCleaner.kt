package com.example.youyou_album.service

import androidx.room.withTransaction
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.db.dao.PhotoDao
import com.example.youyou_album.data.db.dao.ServerExportDao
import com.example.youyou_album.data.db.dao.ServerProjectionDao
import com.example.youyou_album.data.db.dao.ServerSyncStateDao
import com.example.youyou_album.data.db.dao.TagDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程投影属于当前服务端身份，而非设备媒体。
 *
 * 这里故意按“所有远程数据”清理：本机只有一个活跃身份，按 namespace 增量删
 * 会在换同一服务端的另一个账号时留下上一身份的投影。MediaStore 的本机照片
 * 不在本清理范围内。
 */
@Singleton
class RemoteAccountCacheCleaner @Inject constructor(
    private val database: AppDatabase,
    private val photoDao: PhotoDao,
    private val tagDao: TagDao,
    private val serverProjectionDao: ServerProjectionDao,
    private val serverExportDao: ServerExportDao,
    private val serverSyncStateDao: ServerSyncStateDao,
    private val thumbnailCacheService: ThumbnailCacheService,
    private val originalPhotoCacheService: OriginalPhotoCacheService,
) {
    suspend fun clear() {
        database.withTransaction {
            // 先删无外键约束的关联，再删照片和标签。
            tagDao.deleteRelationsForServerPhotos()
            tagDao.deleteRelationsForServerTags()
            photoDao.deleteAllServerPhotos()
            serverProjectionDao.deleteAll()
            serverExportDao.deleteAll()
            tagDao.deleteAllServerTags()
            serverSyncStateDao.clear()
            photoDao.clearRemoteTimes()
        }

        // 这些目录仅存派生预览/原图副本；清空不会触及系统相册。
        thumbnailCacheService.clearAll()
        originalPhotoCacheService.clearAll()
    }
}
