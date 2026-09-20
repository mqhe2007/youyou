package com.example.youyou_album.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.db.entity.PhotoEntity
import com.example.youyou_album.data.db.entity.PhotoTagEntity
import com.example.youyou_album.data.db.entity.TagEntity
import com.example.youyou_album.data.db.entity.ServerMediaProjectionEntity
import com.example.youyou_album.data.db.entity.ServerMediaExportEntity
import com.example.youyou_album.data.db.entity.ServerSyncStateEntity
import com.example.youyou_album.service.ContentHashService
import com.example.youyou_album.service.OriginalPhotoCacheService
import com.example.youyou_album.service.RemoteAccountCacheCleaner
import com.example.youyou_album.service.ThumbnailCacheService
import com.example.youyou_album.util.TestDependencies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据持久化 E2E 测试。
 *
 * 使用手动创建的 Room 内存数据库（不依赖 Hilt），验证核心业务数据操作：
 * Photo CRUD、Tag CRUD + 标签关联、完整业务流程。
 */
@RunWith(AndroidJUnit4::class)
class DatabasePersistenceE2ETest {

    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        database = TestDependencies.createInMemoryDatabase()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ─── Photo CRUD ───

    @Test
    fun photo_upsertAndGetById() = runBlocking {
        val photo = createPhoto(id = "photo_001", name = "sunset.jpg", sortAt = 1000)
        database.photoDao().upsert(photo)

        val fetched = database.photoDao().getById("photo_001")
        assertNotNull(fetched)
        assertEquals("sunset.jpg", fetched!!.name)
        assertEquals(1000L, fetched.sortAt)
        assertFalse(fetched.isVideo)
        assertFalse(fetched.isFavorite)
    }

    @Test
    fun photo_upsertAll_batchInsert() = runBlocking {
        val photos = (1..10).map { i ->
            createPhoto(id = "photo_$i", name = "photo_$i.jpg", sortAt = i.toLong())
        }
        database.photoDao().upsertAll(photos)

        assertEquals(10, database.photoDao().count())
        val all = database.photoDao().getAll()
        assertEquals("photo_10", all.first().id) // sortAt DESC
        assertEquals("photo_1", all.last().id)
    }

    @Test
    fun photo_update_modifiesExisting() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p_update", name = "old.jpg"))
        database.photoDao().update(createPhoto(id = "p_update", name = "new.jpg", isFavorite = true))

        val fetched = database.photoDao().getById("p_update")
        assertEquals("new.jpg", fetched!!.name)
        assertTrue(fetched.isFavorite)
    }

    @Test
    fun photo_deleteById_removesPhoto() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p_del"))
        assertEquals(1, database.photoDao().count())

        database.photoDao().deleteById("p_del")
        assertEquals(0, database.photoDao().count())
        assertNull(database.photoDao().getById("p_del"))
    }

    @Test
    fun photo_deleteByIds_batchRemove() = runBlocking {
        (1..5).forEach { i -> database.photoDao().upsert(createPhoto(id = "p_$i")) }
        database.photoDao().deleteByIds(listOf("p_1", "p_3", "p_5"))

        assertEquals(2, database.photoDao().count())
        assertNotNull(database.photoDao().getById("p_2"))
        assertNull(database.photoDao().getById("p_1"))
    }

    // ─── Photo 查询 ───

    @Test
    fun photo_getBySourceUri() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p_uri", sourceUri = "content://media/123"))

        assertNotNull(database.photoDao().getBySourceUri("content://media/123"))
        assertNull(database.photoDao().getBySourceUri("content://media/999"))
    }

    @Test
    fun photo_getByContentHash() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p_hash", contentHash = "sha256_abc"))
        assertNotNull(database.photoDao().getByContentHash("sha256_abc"))
    }

    @Test
    fun photo_getFavorites() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p1", isFavorite = true, sortAt = 1))
        database.photoDao().upsert(createPhoto(id = "p2", isFavorite = false, sortAt = 2))
        database.photoDao().upsert(createPhoto(id = "p3", isFavorite = true, sortAt = 3))

        val favorites = database.photoDao().observeFavorites("").first()
        assertEquals(2, favorites.size)
        assertTrue(favorites.all { it.isFavorite })
    }

    @Test
    fun photo_updateFavorite_toggles() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p_fav", isFavorite = false))
        database.photoDao().updateFavorite("p_fav", true)
        assertTrue(database.photoDao().getById("p_fav")!!.isFavorite)

        database.photoDao().updateFavorite("p_fav", false)
        assertFalse(database.photoDao().getById("p_fav")!!.isFavorite)
    }

    @Test
    fun photo_getVideos() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p1", isVideo = false, sortAt = 1))
        database.photoDao().upsert(createPhoto(id = "v1", isVideo = true, duration = 30000, sortAt = 2))

        val videos = database.photoDao().getVideos()
        assertEquals(1, videos.size)
        assertEquals("v1", videos.first().id)
    }

    @Test
    fun photo_countMissingContentHash() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p1", contentHash = "hash1"))
        database.photoDao().upsert(createPhoto(id = "p2", contentHash = null))
        database.photoDao().upsert(createPhoto(id = "p3", contentHash = ""))

        assertEquals(2, database.photoDao().countMissingContentHash())
    }

    @Test
    fun photo_observeAll_emitsUpdates() = runBlocking {
        assertEquals(0, database.photoDao().observeAll().first().size)
        database.photoDao().upsert(createPhoto(id = "p_flow", sortAt = 100))
        assertEquals(1, database.photoDao().observeAll().first().size)
    }

    // ─── Tag CRUD ───

    @Test
    fun tag_upsertAndGetById() = runBlocking {
        database.tagDao().upsert(createTag(id = "t1", name = "Nature"))
        assertEquals("Nature", database.tagDao().getById("t1")!!.name)
    }

    @Test
    fun tag_getLocalByName() = runBlocking {
        database.tagDao().upsert(createTag(id = "t1", name = "Nature", sourceType = "local"))
        database.tagDao().upsert(createTag(id = "t2", name = "Nature", sourceType = "server"))

        val found = database.tagDao().getLocalByName("Nature")
        assertNotNull(found)
        assertEquals("t1", found!!.id)
    }

    @Test
    fun tag_deleteById() = runBlocking {
        database.tagDao().upsert(createTag(id = "t_del"))
        database.tagDao().deleteById("t_del")
        assertNull(database.tagDao().getById("t_del"))
    }

    // ─── Tag-Photo 关联 ───

    @Test
    fun tag_addTagToPhoto() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p1", sortAt = 1))
        database.tagDao().upsert(createTag(id = "t1"))
        database.tagDao().addTagToPhoto(PhotoTagEntity("t1", "p1"))

        assertEquals(1, database.tagDao().getTagsForPhoto("p1").size)
        assertEquals(1, database.tagDao().observePhotosByTag("t1", "").first().size)
    }

    @Test
    fun tag_removeTagFromPhoto() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p1", sortAt = 1))
        database.tagDao().upsert(createTag(id = "t1"))
        database.tagDao().addTagToPhoto(PhotoTagEntity("t1", "p1"))
        database.tagDao().removeTagFromPhoto("t1", "p1")

        assertEquals(0, database.tagDao().getTagsForPhoto("p1").size)
    }

    @Test
    fun tag_multipleTagsOnPhoto() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "p1", sortAt = 1))
        database.tagDao().upsert(createTag(id = "t1", name = "Nature"))
        database.tagDao().upsert(createTag(id = "t2", name = "Travel"))
        database.tagDao().addTagToPhoto(PhotoTagEntity("t1", "p1"))
        database.tagDao().addTagToPhoto(PhotoTagEntity("t2", "p1"))

        val tags = database.tagDao().getTagsForPhoto("p1")
        assertEquals(2, tags.size)
        assertTrue(tags.any { it.name == "Nature" })
        assertTrue(tags.any { it.name == "Travel" })
    }

    // ─── 完整业务流程 ───

    @Test
    fun fullWorkflow_photoWithTag() = runBlocking {
        // 1. 创建照片
        database.photoDao().upsert(createPhoto(id = "photo_full", name = "beach.jpg", sortAt = 1000))

        // 2. 创建标签并关联照片
        database.tagDao().upsert(createTag(id = "tag_full", name = "Sunset"))
        database.tagDao().addTagToPhoto(PhotoTagEntity("tag_full", "photo_full"))

        // 3. 验证关联
        assertEquals(1, database.tagDao().getTagsForPhoto("photo_full").size)
        assertEquals(1, database.tagDao().observePhotosByTag("tag_full", "").first().size)

        // 4. 收藏照片
        database.photoDao().updateFavorite("photo_full", true)
        assertTrue(database.photoDao().observeFavorites("").first().any { it.id == "photo_full" })
    }

    @Test
    fun remoteAccountCleanup_keepsDeviceMediaAndRemovesRemoteIdentityData() = runBlocking {
        database.photoDao().upsert(createPhoto(id = "device_photo", contentHash = "local-hash"))
        database.photoDao().upsert(
            createPhoto(id = "remote_photo", contentHash = "remote-hash").copy(sourceType = "server")
        )
        database.serverProjectionDao().upsert(
            ServerMediaProjectionEntity("server_a", "media_a", "remote_photo", 1, updatedAt = 1)
        )
        database.tagDao().upsert(
            TagEntity("remote_tag", "远程标签", 1, 1, sourceType = "server", serverNamespace = "server_a")
        )
        database.tagDao().upsert(createTag(id = "local_tag", name = "本地标签"))
        database.tagDao().upsert(
            createTag(id = "t" + "a".repeat(32), name = "历史远程标签")
        )
        database.tagDao().addTagToPhoto(PhotoTagEntity("remote_tag", "remote_photo", "server_a"))
        database.tagDao().addTagToPhoto(PhotoTagEntity("local_tag", "device_photo"))
        database.serverExportDao().upsert(
            ServerMediaExportEntity(
                serverNamespace = "server_a",
                serverMediaId = "media_a",
                sourceUri = "content://remote",
                createdAt = 1,
                updatedAt = 1,
            )
        )
        database.serverSyncStateDao().upsert(ServerSyncStateEntity(serverNamespace = "server_a", changesCursor = "cursor", snapshotRevision = 1, updatedAt = 1))

        RemoteAccountCacheCleaner(
            database = database,
            photoDao = database.photoDao(),
            tagDao = database.tagDao(),
            serverProjectionDao = database.serverProjectionDao(),
            serverExportDao = database.serverExportDao(),
            serverSyncStateDao = database.serverSyncStateDao(),
            thumbnailCacheService = ThumbnailCacheService(TestDependencies.context),
            originalPhotoCacheService = OriginalPhotoCacheService(
                TestDependencies.context,
                ContentHashService(TestDependencies.context),
            ),
        ).clear()

        assertNotNull(database.photoDao().getById("device_photo"))
        assertNull(database.photoDao().getById("remote_photo"))
        assertEquals(0, database.serverProjectionDao().listLocalPhotoIds("server_a").size)
        assertNull(database.serverSyncStateDao().get())
        assertNull(database.tagDao().getById("remote_tag"))
        assertNotNull(database.tagDao().getById("local_tag"))
        assertEquals(1, database.tagDao().getTagsForPhoto("device_photo").size)
    }

    // ─── 辅助方法 ───

    private fun createPhoto(
        id: String,
        name: String = "test.jpg",
        path: String = "/photos/test.jpg",
        sortAt: Long = 0,
        createdAt: Long? = null,
        isVideo: Boolean = false,
        duration: Int? = null,
        sourceUri: String? = null,
        contentHash: String? = null,
        isFavorite: Boolean = false,
    ) = PhotoEntity(
        id = id, name = name, path = path, sourceType = "local",
        storageId = null, thumbnailPath = null, isVideo = isVideo, duration = duration,
        createdAt = createdAt ?: sortAt, modifiedAt = createdAt ?: sortAt, sortAt = sortAt,
        width = 1920, height = 1080, size = 1024000, mimeType = "image/jpeg",
        exifData = null, sourceUri = sourceUri, contentHash = contentHash,
        syncedAt = null, syncedStorageId = null, syncedRemotePath = null, isFavorite = isFavorite,
    )

    private fun createTag(
        id: String, name: String = "Test Tag", sourceType: String = "local",
    ) = TagEntity(
        id = id, name = name, createdAt = 1000, updatedAt = 1000,
        sourceType = sourceType, serverNamespace = null,
    )
}
