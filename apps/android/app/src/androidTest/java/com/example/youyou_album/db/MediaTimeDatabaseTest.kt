package com.example.youyou_album.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.db.dao.TIMELINE_FIRST_SCREEN
import com.example.youyou_album.data.db.entity.PhotoEntity
import com.example.youyou_album.data.db.entity.ServerMediaProjectionEntity
import com.example.youyou_album.data.db.entity.ServerSyncStateEntity
import com.example.youyou_album.data.db.entity.TimelineTimeEntity
import com.example.youyou_album.data.db.migration.MIGRATION_4_5
import com.example.youyou_album.data.db.migration.MIGRATION_5_6
import com.example.youyou_album.data.db.migration.MIGRATION_6_7
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * File-backed Room/SQLite contract tests, not UI/E2E substitutes.
 *
 * 性能门槛按**真机实测口径**判定（基准机 NOH-AN01 / HarmonyOS Android 12 / API 31，SQLite 3.32.2）；
 * 模拟器结果只作参考，不作为判据。门槛值来源与优化前后对照见
 * 十万行量级下的耗时门槛与优化依据见本文件断言。
 */
@RunWith(AndroidJUnit4::class)
class MediaTimeDatabaseTest {
    @Test fun canonicalTimeSurvivesRepresentativeChangeAndSeparatesAccounts() = runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val name="time-contract-${System.nanoTime()}.db"
        val db=Room.databaseBuilder(ctx,AppDatabase::class.java,name).build()
        try {
            val dao=db.photoDao()
            val at=1704067200000L
            db.serverSyncStateDao().upsert(ServerSyncStateEntity(1,"account-a","cursor",1,1))
            dao.upsert(PhotoEntity(id="remote",name="plain.jpg",path="remote",sourceType="server",contentHash="same",sortAt=at,sortSource="modified",timeVersion=1))
            db.serverProjectionDao().upsert(ServerMediaProjectionEntity("account-a","server-id","remote",1,1,"local",1))
            dao.reconcileTime("same")
            assertEquals(at,dao.observeTimeline("ALL","account-a").first().single().timelineAt)
            dao.upsertWithTime(PhotoEntity(id="local",name="plain.jpg",path="local",sourceUri="content://media/external/images/media/1",contentHash="same",sortAt=at+1000,sortSource="modified",timeVersion=1))
            val merged=dao.observeTimeline("ALL","account-a").first().single()
            assertEquals("local",merged.photo.id)
            assertEquals("remote",merged.timelineKey)
            assertEquals(at,merged.timelineAt)
            assertEquals("SYNCED",merged.backupState)
            assertEquals(at+1000,dao.observeTimeline("ALL","account-b").first().single().timelineAt)
            dao.upsertWithTime(merged.photo.copy(sortAt=at-1000,sortSource="capture",takenAt=at-1000))
            assertEquals(at-1000,dao.observeTimeline("ALL","account-a").first().single().timelineAt)
            dao.upsertWithTime(PhotoEntity(id="unknown",name="unknown",path="unknown"))
            assertEquals("unknown",dao.observeTimeline("ALL","account-a").first().last().photo.id)
            dao.clearRemoteTimes()
            assertNull(dao.canonicalTime("account-a","same"))
        } finally { db.close();ctx.deleteDatabase(name) }
    }

    @Test fun versionFourMigrationPreservesRecordsAndScalesToOneHundredThousand() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val ctx=instrumentation.targetContext
        val name="time-migration-${System.nanoTime()}.db"
        val schema=JSONObject(instrumentation.context.assets.open("time-schema-v4.json").bufferedReader().readText()).getJSONObject("database")
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name).callback(object:SupportSQLiteOpenHelper.Callback(4){
            override fun onCreate(db:SupportSQLiteDatabase) {
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {
                    val e=entities.getJSONObject(i)
                    db.execSQL(e.getString("createSql").replace("\u0024{TABLE_NAME}",e.getString("tableName")))
                    val indices=e.optJSONArray("indices") ?: org.json.JSONArray()
                    for(j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}",e.getString("tableName")))
                }
                val setup=schema.getJSONArray("setupQueries")
                for(i in 0 until setup.length()) db.execSQL(setup.getString(i))
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) = Unit
        }).build())
        try {
            val raw=helper.writableDatabase
            raw.beginTransaction()
            try {
                val stmt=raw.compileStatement("INSERT INTO photos_table(id,name,path,source_type,is_video,is_favorite,sort_at,content_hash) VALUES(?,?,'/Pictures/original','local',0,1,1789186887000,?)")
                for(i in 0 until 100000) {
                    stmt.bindString(1,"id-%06d".format(i));stmt.bindString(2,"IMG_20240101_000000_h%06x.jpg".format(i));stmt.bindString(3,"hash-$i");stmt.executeInsert()
                }
                stmt.close();raw.setTransactionSuccessful()
            } finally {raw.endTransaction()}
            helper.close()
            val started=System.nanoTime()
            val db=Room.databaseBuilder(ctx,AppDatabase::class.java,name).addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
            try {
                val migrated=db.openHelper.writableDatabase
                val elapsed=(System.nanoTime()-started)/1000000
                migrated.query("SELECT COUNT(*),MIN(sort_at),MAX(sort_at),SUM(is_favorite) FROM photos_table").use {
                    assertTrue(it.moveToFirst());assertEquals(100000,it.getInt(0));assertEquals(1704067200000L,it.getLong(1));assertEquals(it.getLong(1),it.getLong(2));assertEquals(100000,it.getInt(3))
                }
                val baselineStart=System.nanoTime()
                runBlocking { assertEquals(100000, db.photoDao().getAll().size) }
                val baselineQueryMs=(System.nanoTime()-baselineStart)/1000000
                // 首屏：第一段发射（窗口）即可上屏，用户不必等全量补齐。
                val firstScreenStart=System.nanoTime()
                val firstScreenSize=runBlocking { db.photoDao().observeTimeline("ALL","").first().size }
                val firstScreenMs=(System.nanoTime()-firstScreenStart)/1000000
                // 全量：等第二段发射补齐到十万行。
                val startQuery=System.nanoTime()
                runBlocking { assertEquals(100000,db.photoDao().observeTimeline("ALL","").first { it.size==100000 }.size) }
                val queryMs=(System.nanoTime()-startQuery)/1000000
                val device="${android.os.Build.MODEL} API ${android.os.Build.VERSION.SDK_INT}"
                android.util.Log.i("MediaTimeBenchmark","100000 file-backed rows device=$device migrationMs=$elapsed baselineQueryMs=$baselineQueryMs firstScreenRows=$firstScreenSize firstScreenMs=$firstScreenMs timelineQueryMs=$queryMs")
                assertTrue("Migration exceeded 30s on $device: $elapsed",elapsed<30000)
                assertTrue("Timeline first screen exceeded 1s on $device: $firstScreenMs",firstScreenMs<1000)
                assertTrue("Timeline query exceeded 5s on $device: $queryMs",queryMs<5000)
            } finally { db.close() }
        } finally {helper.close();ctx.deleteDatabase(name)}
    }

    /**
     * 时间线顺序的回归钉子：数据集的排序顺序刻意与主键顺序相反。
     *
     * 时间线已把「排序」与「取整行」拆成两段（窄列排序取有序 id → 顺序扫描整表 → 内存按有序 id 重排）。
     * 任一段丢序——SQLite 优化器丢弃 ORDER BY、或重排用了错误的键——结果都会退化成主键序。
     * `sort_at` 全部相等的扁平数据集无法发现这类退化（此时正确的决胜键恰好就是主键），
     * 因此本用例让 `sort_at` 随序号递增，使正确顺序与主键序完全相反。
     *
     * 另外覆盖两处决胜语义：同一 `sort_at` 时按 `timelineKey` 升序，`timeline_times` 有规范时间时以它为准。
     */
    @Test fun timelineOrderFollowsSortAtAndCanonicalTimeNotPrimaryKey() = runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val name="time-order-${System.nanoTime()}.db"
        val db=Room.databaseBuilder(ctx,AppDatabase::class.java,name).build()
        try {
            val dao=db.photoDao()
            val base=1789186887000L
            db.openHelper.writableDatabase.beginTransaction()
            try {
                val stmt=db.openHelper.writableDatabase.compileStatement(
                    "INSERT INTO photos_table(id,name,path,source_type,is_video,is_favorite,sort_at,sort_source,content_hash) " +
                        "VALUES(?,?,'/Pictures/original','local',0,0,?,'filename',?)",
                )
                for(i in 0 until 100000) {
                    stmt.bindString(1,"id-%06d".format(i))
                    stmt.bindString(2,"IMG_20240101_000000_h%06x.jpg".format(i))
                    stmt.bindLong(3,base+i)
                    stmt.bindString(4,"hash-$i")
                    stmt.executeInsert()
                }
                stmt.close()
                db.openHelper.writableDatabase.setTransactionSuccessful()
            } finally { db.openHelper.writableDatabase.endTransaction() }

            val all=dao.observeTimeline("ALL","").first { it.size==100000 }
            // sort_at 递增 → 时间线为降序，与主键升序完全相反。
            assertEquals("id-099999",all.first().photo.id)
            assertEquals("id-000000",all.last().photo.id)
            assertEquals(base+99999L,all.first().timelineAt)
            assertEquals(base,all.last().timelineAt)
            assertEquals(listOf("id-099999","id-099998","id-099997"),all.take(3).map { it.photo.id })
            // 两段式回表不得丢行或重复：十万行全部按序取回。
            assertEquals(100000,all.size)
            assertEquals(100000,all.map { it.photo.id }.toSet().size)
            // observeTimeline 是分段的：第一次发射只有首屏窗口。需要完整集合的调用方
            // （幻灯片 / 查看器）必须走 getTimelineOnce，不能拿 .first() 当全量。
            assertEquals(TIMELINE_FIRST_SCREEN,dao.observeTimeline("ALL","").first().size)
            assertEquals(100000,dao.getTimelineOnce("ALL","").size)

            // 同一 sort_at 时按 timelineKey 升序：让 id-000001 与 id-000000 同刻（都是最小时间），
            // 决胜键取 id，故该并列组内 id-000000 在前——这一组位于降序时间线的末尾。
            dao.upsert(PhotoEntity(id="id-000001",name="IMG_20240101_000000_h000001.jpg",path="/Pictures/original",sourceType="local",sortAt=base,sortSource="filename",contentHash="hash-1"))
            dao.reconcileTime("hash-1")
            val tied=dao.observeTimeline("ALL","").first { it.size==100000 }
            assertEquals(listOf("id-000000","id-000001"),tied.takeLast(2).map { it.photo.id })
            assertEquals(listOf(base,base),tied.takeLast(2).map { it.timelineAt })

            // timeline_times 有规范时间时以它为准：把 id-000000 的规范时间抬到最高，它应升到首位。
            dao.putCanonicalTime(TimelineTimeEntity("","hash-0",base+999999L,"capture","id-000000"))
            val overridden=dao.observeTimeline("ALL","").first { it.size==100000 }
            assertEquals("id-000000",overridden.first().photo.id)
            assertEquals(base+999999L,overridden.first().timelineAt)
            assertEquals("capture",overridden.first().timelineSource)
        } finally { db.close();ctx.deleteDatabase(name) }
    }
}
