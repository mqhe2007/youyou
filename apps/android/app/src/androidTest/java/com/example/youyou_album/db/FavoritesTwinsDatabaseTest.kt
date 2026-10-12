package com.example.youyou_album.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.db.entity.PhotoEntity
import com.example.youyou_album.data.db.entity.ServerMediaProjectionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 收藏跨端一致性的数据库契约：
 *  * 收藏按内容哈希作用于本机孪生行与当前身份的服务端投影行；
 *  * 其他身份的服务端行不受牵连；
 *  * 收藏页隐藏动态部分行，且只展示当前身份内的服务端收藏。
 */
@RunWith(AndroidJUnit4::class)
class FavoritesTwinsDatabaseTest {
    @Test fun favoritePropagatesAcrossHashTwinsWithinCurrentIdentity() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "favorites-twins-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).build()
        try {
            val dao = db.photoDao()
            dao.upsert(PhotoEntity(id = "local", name = "a.jpg", path = "", contentHash = "h1"))
            dao.upsert(PhotoEntity(id = "server", name = "a.jpg", path = "remote", sourceType = "server", contentHash = "h1"))
            db.serverProjectionDao().upsert(ServerMediaProjectionEntity("ns", "m1", "server", 1, 1, "local", 1))
            dao.upsert(PhotoEntity(id = "other-server", name = "a.jpg", path = "remote", sourceType = "server", contentHash = "h1"))
            db.serverProjectionDao().upsert(ServerMediaProjectionEntity("other", "m2", "other-server", 1, 1, "local", 1))
            dao.upsert(PhotoEntity(id = "motion", name = "a.mov", path = "", contentHash = "hm", liveRole = "motion"))

            // 用户在本机行上点收藏：服务端投影行一起翻转，其他身份不受影响。
            dao.updateFavoriteByHash("h1", true, "ns")
            assertTrue(dao.getById("local")!!.isFavorite)
            assertTrue(dao.getById("server")!!.isFavorite)
            assertFalse(dao.getById("other-server")!!.isFavorite)

            // 服务端收藏下行：本机孪生行跟随载荷；服务端行由载荷自身维护。
            dao.applyServerFavoriteToLocals("h1", false)
            assertFalse(dao.getById("local")!!.isFavorite)
            assertTrue(dao.getById("server")!!.isFavorite)

            // 收藏页：动态部分行不出现；其他身份先收藏的媒体也不出现。
            dao.updateFavorite("motion", true)
            dao.updateFavorite("other-server", true)
            val favorites = dao.observeFavorites("ns").first().map { it.id }.toSet()
            assertEquals(setOf("server"), favorites)
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }
}
