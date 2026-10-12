package com.example.youyou_album.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.db.entity.PhotoEntity
import com.example.youyou_album.data.db.entity.PhotoTagEntity
import com.example.youyou_album.data.db.entity.ServerMediaProjectionEntity
import com.example.youyou_album.data.db.entity.ServerSyncStateEntity
import com.example.youyou_album.data.db.entity.TagEntity
import com.example.youyou_album.data.repository.PhotoRepositoryImpl
import com.example.youyou_album.data.repository.TagRepositoryImpl
import com.example.youyou_album.service.ServerConnectionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 打标/取消的归属解析：服务端下行的标签关联挂在服务端投影行上，时间线里可见的
 * 是同一内容哈希的本机行——[TagRepositoryImpl.getTagsForPhoto] 必须把两侧合并，
 * 信息面板才能看到照片真实的标签。
 */
@RunWith(AndroidJUnit4::class)
class TagMembershipDatabaseTest {
    @Test fun tagsAttachedToServerTwinAreVisibleFromLocalRow() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "tag-membership-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).build()
        try {
            val store = ServerConnectionStore(ctx)
            val photoRepository = PhotoRepositoryImpl(
                db.photoDao(), db.serverProjectionDao(), db.serverSyncStateDao(), store,
            )
            val tagRepository = TagRepositoryImpl(
                db.tagDao(), db.serverSyncStateDao(), db.serverProjectionDao(), store, photoRepository,
            )
            val namespace = "server_twin"
            db.serverSyncStateDao().upsert(ServerSyncStateEntity(1, namespace, "cursor", 1, 1))
            db.photoDao().upsert(PhotoEntity(id = "local", name = "a.jpg", path = "", contentHash = "h1"))
            db.photoDao().upsert(
                PhotoEntity(id = "server", name = "a.jpg", path = "remote", sourceType = "server", contentHash = "h1"),
            )
            db.serverProjectionDao().upsert(ServerMediaProjectionEntity(namespace, "m1", "server", 1, 1, "local", 1))
            db.tagDao().upsert(
                TagEntity("t1", "旅行", 1, 1, sourceType = "server", serverNamespace = namespace, serverRemoteId = "r1", serverVersion = 1),
            )
            // 关联挂在服务端投影行（同步下行的落点）。
            db.tagDao().addTagToPhoto(PhotoTagEntity("t1", "server", namespace))

            val localPhoto = photoRepository.getById("local")!!
            assertEquals(listOf("旅行"), tagRepository.getTagsForPhoto(localPhoto).map { it.name })
            val serverPhoto = photoRepository.getById("server")!!
            assertEquals(listOf("旅行"), tagRepository.getTagsForPhoto(serverPhoto).map { it.name })

            // 关联移除后两个入口都不再显示。
            db.tagDao().removeTagFromPhoto("t1", "server")
            assertTrue(tagRepository.getTagsForPhoto(localPhoto).isEmpty())
            assertTrue(tagRepository.getTagsForPhoto(serverPhoto).isEmpty())
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }
}
