package com.example.youyou_album.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.db.entity.PhotoEntity
import com.example.youyou_album.data.db.entity.PhotoTagEntity
import com.example.youyou_album.data.db.entity.ServerMediaExportEntity
import com.example.youyou_album.data.db.entity.ServerMediaProjectionEntity
import com.example.youyou_album.data.db.entity.ServerSyncStateEntity
import com.example.youyou_album.data.db.entity.TagEntity
import com.example.youyou_album.service.isLegacyStyleNamespace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * 命名空间升级迁移的数据库契约：旧式命名空间（仅地址哈希）被判定为可迁移时，
 * 投影/标签/标签关联/导出/同步状态整批改名且保留游标（不触发全量重建）。
 */
@RunWith(AndroidJUnit4::class)
class NamespaceMigrationDatabaseTest {
    private val sha256Hex: (String) -> String = { input ->
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Test fun legacyNamespaceRenamesAllIdentityRowsAndKeepsCursor() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "namespace-migration-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).build()
        try {
            val baseUrl = "http://10.0.2.2:8989"
            val oldNamespace = "server_" + sha256Hex(baseUrl).substring(0, 16)
            val newNamespace = "server_" + sha256Hex("$baseUrl\ninstance-1\n7").substring(0, 16)
            assertNotEquals(oldNamespace, newNamespace)

            db.photoDao().upsert(
                PhotoEntity(id = "remote", name = "a.jpg", path = "remote", sourceType = "server", contentHash = "h1"),
            )
            db.serverProjectionDao().upsert(ServerMediaProjectionEntity(oldNamespace, "m1", "remote", 3, 5, "local", 1))
            db.tagDao().upsert(
                TagEntity("tag1", "旅行", 1, 1, sourceType = "server", serverNamespace = oldNamespace, serverRemoteId = "r1", serverVersion = 2),
            )
            db.tagDao().addTagToPhoto(PhotoTagEntity("tag1", "remote", oldNamespace))
            db.serverExportDao().upsert(
                ServerMediaExportEntity(
                    serverNamespace = oldNamespace,
                    serverMediaId = "m1",
                    sourceUri = "content://export/1",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            db.serverSyncStateDao().upsert(ServerSyncStateEntity(1, oldNamespace, "cursor-1", 9, 1))

            // 判定：确实是旧式命名空间才迁移。
            assertTrue(isLegacyStyleNamespace(oldNamespace, baseUrl, sha256Hex))

            // 与 ServerSyncService.migrateLegacyNamespace 相同的组合动作。
            db.withTransaction {
                db.serverProjectionDao().renameNamespace(oldNamespace, newNamespace)
                db.tagDao().renameNamespace(oldNamespace, newNamespace)
                db.tagDao().renameRelationNamespace(oldNamespace, newNamespace)
                db.serverExportDao().renameNamespace(oldNamespace, newNamespace)
                db.serverSyncStateDao().upsert(
                    db.serverSyncStateDao().get()!!.copy(serverNamespace = newNamespace, updatedAt = 2),
                )
            }

            assertNotNull(db.serverProjectionDao().getByMediaId(newNamespace, "m1"))
            assertNull(db.serverProjectionDao().getByMediaId(oldNamespace, "m1"))
            assertEquals(1, db.serverExportDao().getByMediaId(newNamespace, "m1").size)
            val tag = db.tagDao().getById("tag1")!!
            assertEquals(newNamespace, tag.serverNamespace)
            assertEquals("r1", tag.serverRemoteId)
            assertEquals(2L, tag.serverVersion)
            val state = db.serverSyncStateDao().get()!!
            assertEquals(newNamespace, state.serverNamespace)
            assertEquals("cursor-1", state.changesCursor)
            assertEquals(9, state.snapshotRevision)

            db.query("SELECT server_namespace FROM photo_tags_table WHERE tag_id = 'tag1'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(newNamespace, cursor.getString(0))
            }
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }
}
