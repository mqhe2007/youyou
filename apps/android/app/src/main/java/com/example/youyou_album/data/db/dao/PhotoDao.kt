package com.example.youyou_album.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.example.youyou_album.data.db.entity.TimelineTimeEntity
import com.example.youyou_album.domain.model.MediaTime
import com.example.youyou_album.data.db.entity.PhotoEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** 时间线行：媒体 + 派生的备份状态（哈希比对，服务端为事实来源）。 */
data class TimelinePhoto(
    @Embedded val photo: PhotoEntity,
    val backupState: String,
    val timelineAt: Long? = null,
    val timelineSource: String? = null,
    val timelineKey: String = "",
)

/**
 * 时间线排序键：只含排序与三态派生所需列。
 *
 * 时间线原先直接对 `p.*`（26 列，含 name/path/exif_data/original_name 等多个长文本）排序。
 * 真机（NOH-AN01 / API 31）按生产形态实测十万行：同 JOIN、同排序表达式下，宽行排序 8987ms，
 * 而排序器只搬运 5 列时为 1109ms——成本在排序器逐行把整行负载复制进临时 B 树，
 * 与索引、页缓存、Room 映射都无关（加页缓存到 64MB 无变化，Room 映射 100000 行仅约 950ms）。
 *
 * 因此把「排序」与「取整行」拆开：先用本类型拿到有序 id 与派生状态，再回填完整行。
 */
data class TimelineOrder(
    val id: String,
    val timelineKey: String,
    val timelineAt: Long?,
    val timelineSource: String?,
    val backupState: String,
) {
    fun toTimelinePhoto(photo: PhotoEntity) =
        TimelinePhoto(photo, backupState, timelineAt, timelineSource, timelineKey)
}

/** 首屏窗口行数：一屏网格约 20-40 行，200 足以覆盖滚动前的可见区。 */
const val TIMELINE_FIRST_SCREEN = 200

/** SQLite 绑定变量上限的保守取值（Android 12 的 SQLite 3.32 默认 SQLITE_MAX_VARIABLE_NUMBER=999）。 */
private const val SQLITE_MAX_BIND_ARGS = 900

@Dao
@OptIn(ExperimentalCoroutinesApi::class)
interface PhotoDao {

    @Query("SELECT * FROM photos_table p WHERE p.content_hash=:hash AND (p.source_type!='server' OR EXISTS(SELECT 1 FROM server_media_projection sp WHERE sp.local_photo_id=p.id AND sp.server_namespace=:namespace))")
    suspend fun timePeers(hash: String, namespace: String): List<PhotoEntity>

    @Query("DELETE FROM timeline_times WHERE namespace != ''")
    suspend fun clearRemoteTimes()

    @Query("SELECT COALESCE((SELECT server_namespace FROM server_sync_state WHERE id=1),'')")
    suspend fun timeNamespace(): String

    @Query("SELECT * FROM timeline_times WHERE namespace=:namespace AND hash=:hash")
    suspend fun canonicalTime(namespace: String, hash: String): TimelineTimeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCanonicalTime(time: TimelineTimeEntity)

    @Transaction
    suspend fun reconcileTime(hash: String?) {
        reconcileTimeForNamespace(hash, timeNamespace())
    }

    @Transaction
    suspend fun reconcileTimeForNamespace(hash: String?, namespace: String) {
        if (hash.isNullOrBlank()) return
        val peers = timePeers(hash, namespace)
        val best = peers
            .filter { MediaTime.valid(it.sortAt) != null }
            .minWithOrNull(
                compareByDescending<PhotoEntity> { MediaTime.rank(it.sortSource) }
                    .thenBy { it.sortAt }
                    .thenBy { it.id },
            ) ?: return
        val old = canonicalTime(namespace, hash)
        val chosen = MediaTime.choose(
            old?.let { MediaTime.Value(it.at, it.source) },
            MediaTime.Value(best.sortAt, best.sortSource),
        )
        val oldAt = old?.at
        val oldSource = old?.source
        val oldSortKey = old?.sortKey
        val key = oldSortKey?.takeIf { it.isNotEmpty() }
            ?: peers.filter { it.sourceType != "server" }.minOfOrNull { it.id } ?: best.id
        if (oldAt != chosen.at || oldSource != chosen.source || oldSortKey != key) {
            putCanonicalTime(TimelineTimeEntity(namespace, hash, chosen.at, chosen.source, key))
        }
    }

    @Transaction
    suspend fun upsertWithTime(photo: PhotoEntity) {
        upsert(photo)
        if (photo.sourceType != "server") reconcileTime(photo.contentHash)
    }

    @Transaction
    suspend fun upsertAllWithTime(photos: List<PhotoEntity>) {
        upsertAll(photos)
        photos.mapNotNull { it.contentHash }.distinct().forEach { reconcileTime(it) }
    }

    @Transaction
    suspend fun fillHashWithTime(id: String, uri: String, modifiedAt: Long?, size: Long?, hash: String) {
        if (fillContentHash(id, uri, modifiedAt, size, hash) > 0) reconcileTime(hash)
    }

    @Query("SELECT * FROM photos_table ORDER BY sort_at DESC, COALESCE(content_hash,id), id")
    fun observeAll(): Flow<List<PhotoEntity>>

    /**
     * 合并时间线：本机索引全量展示 + 服务端投影按内容哈希去重（本地行优先）。
     *
     * `namespace` 为**当前**服务端身份（`server_sync_state.server_namespace`）。服务端行
     * 只有在属于当前身份时才算可见——`server_namespace` 由 baseUrl 派生，同一台设备历史上
     * 绑定过的每个地址都会留下独立的一批投影行，不限定作用域会让旧身份的投影混入时间线。
     * 传空串表示当前未绑定（或尚未同步），此时不返回任何服务端行，只浏览本机媒体。
     *
     * 备份状态派生：
     * - 服务端行：当前身份内，本机有同哈希行 → SYNCED（但已被下方隐藏），否则 REMOTE_ONLY
     * - 本机行：哈希未知，或当前身份内无同哈希服务端行 → LOCAL_ONLY，否则 SYNCED
     */
    /** 未绑定服务端时的有序排序键（本机行全量展示，不返回任何服务端投影）。 */
    @Query(
        """
        SELECT p.id AS id,
            COALESCE(NULLIF(tt.sortKey,''), p.id) AS timelineKey,
            CASE WHEN tt.hash IS NOT NULL THEN tt.at ELSE p.sort_at END AS timelineAt,
            CASE WHEN tt.hash IS NOT NULL THEN tt.source ELSE p.sort_source END AS timelineSource,
            'LOCAL_ONLY' AS backupState
        FROM photos_table p
        LEFT JOIN timeline_times tt
            ON tt.hash = p.content_hash
           AND tt.namespace = ''
        WHERE p.source_type != 'server'
          AND COALESCE(p.live_role, 'none') != 'motion'
          AND (:filter = 'ALL' OR :filter = 'LOCAL_ONLY')
        ORDER BY timelineAt DESC, timelineKey ASC, p.id ASC
        """,
    )
    fun observeUnboundOrder(filter: String): Flow<List<TimelineOrder>>

    /** 未绑定服务端时的首屏窗口：同一排序只取前 [limit] 行。 */
    @Query(
        """
        SELECT p.id AS id,
            COALESCE(NULLIF(tt.sortKey,''), p.id) AS timelineKey,
            CASE WHEN tt.hash IS NOT NULL THEN tt.at ELSE p.sort_at END AS timelineAt,
            CASE WHEN tt.hash IS NOT NULL THEN tt.source ELSE p.sort_source END AS timelineSource,
            'LOCAL_ONLY' AS backupState
        FROM photos_table p
        LEFT JOIN timeline_times tt
            ON tt.hash = p.content_hash
           AND tt.namespace = ''
        WHERE p.source_type != 'server'
          AND COALESCE(p.live_role, 'none') != 'motion'
          AND (:filter = 'ALL' OR :filter = 'LOCAL_ONLY')
        ORDER BY timelineAt DESC, timelineKey ASC, p.id ASC
        LIMIT :limit
        """,
    )
    fun observeUnboundWindow(filter: String, limit: Int): Flow<List<TimelineOrder>>

    /**
     * 未绑定服务端的时间线：先出首屏窗口，再补齐全量。
     *
     * 两段是同一排序的前缀关系，后段只在尾部增长，首屏不会因补齐而换位。
     */
    fun observeUnboundTimeline(filter: String): Flow<List<TimelinePhoto>> =
        observeUnboundWindow(filter, TIMELINE_FIRST_SCREEN).flatMapLatest { window ->
            flow {
                emit(hydrateByIds(window))
                emit(hydrateByScan(observeUnboundOrder(filter).first(), includeServerRows = false))
            }
        }

    /** 未绑定服务端时无需执行服务端去重 CTE；直接走本机行的时间线查询。 */
    fun observeTimeline(filter: String, namespace: String): Flow<List<TimelinePhoto>> =
        if (namespace.isEmpty()) observeUnboundTimeline(filter)
        else observeServerScopedTimeline(filter, namespace)

    /**
     * 绑定服务端时的有序排序键：本机行 + 当前身份可见的服务端投影（按内容哈希去重）。
     *
     * `namespace` 为**当前**服务端身份（`server_sync_state.server_namespace`）。服务端行
     * 只有在属于当前身份时才算可见——`server_namespace` 由 baseUrl 派生，同一台设备历史上
     * 绑定过的每个地址都会留下独立的一批投影行，不限定作用域会让旧身份的投影混入时间线。
     * 传空串表示当前未绑定（或尚未同步），此时不返回任何服务端行，只浏览本机媒体。
     *
     * 备份状态派生：
     * - 服务端行：当前身份内，本机有同哈希行 → SYNCED（但已被下方隐藏），否则 REMOTE_ONLY
     * - 本机行：哈希未知，或当前身份内无同哈希服务端行 → LOCAL_ONLY，否则 SYNCED
     */
    @Query(
        """
        WITH local_hashes AS (
            SELECT content_hash AS hash
            FROM photos_table
            WHERE :namespace != '' AND source_type != 'server' AND content_hash IS NOT NULL
            GROUP BY content_hash
        ),
        -- 远程存在的部分（FR-4）：服务端投影行的自身哈希 + 其已入库的动态部分。
        -- 服务端清单不投影动态部分，「动态部分已在服务端」只能由 live_partner_id 非空推出。
        present_remote AS (
            SELECT DISTINCT s.content_hash AS hash
            FROM photos_table s
            WHERE s.source_type = 'server' AND s.content_hash IS NOT NULL
              AND EXISTS(
                  SELECT 1 FROM server_media_projection sp
                  WHERE sp.local_photo_id = s.id AND sp.server_namespace = :namespace
              )
            UNION
            SELECT DISTINCT s.live_partner_hash AS hash
            FROM photos_table s
            WHERE s.source_type = 'server'
              AND s.live_partner_id IS NOT NULL
              AND s.live_partner_hash IS NOT NULL
              AND EXISTS(
                  SELECT 1 FROM server_media_projection sp
                  WHERE sp.local_photo_id = s.id AND sp.server_namespace = :namespace
              )
        ),
        visible AS (
            SELECT p.*
            FROM photos_table p
            WHERE p.source_type != 'server'
              AND COALESCE(p.live_role, 'none') != 'motion'
            UNION ALL
            SELECT p.*
            FROM photos_table p
            WHERE p.source_type = 'server'
              AND COALESCE(p.live_role, 'none') != 'motion'
              AND EXISTS(
                  SELECT 1 FROM server_media_projection own
                  WHERE own.local_photo_id = p.id
                    AND own.server_namespace = :namespace
              )
              AND (
                  p.content_hash IS NULL
                  OR NOT EXISTS(
                      SELECT 1 FROM local_hashes lh
                      WHERE lh.hash = p.content_hash
                  )
              )
        ),
        -- 三态按「实况整体」派生：任一部分仅本机→仅本机；无仅本机部分且任一部分仅远程→仅远程；
        -- 全部部分两端同哈希→已同步。普通媒体退化为只看自身哈希。
        unit_hashes AS (
            SELECT v.id AS row_id, v.content_hash AS hash FROM visible v WHERE v.content_hash IS NOT NULL
            UNION ALL
            SELECT v.id AS row_id, v.live_partner_hash AS hash FROM visible v WHERE v.live_partner_hash IS NOT NULL
        ),
        unit_state AS (
            SELECT uh.row_id AS row_id,
                   MAX(CASE WHEN lh.hash IS NULL THEN 1 ELSE 0 END) AS any_missing_local,
                   MAX(CASE WHEN pr.hash IS NULL THEN 1 ELSE 0 END) AS any_missing_remote
            FROM unit_hashes uh
            LEFT JOIN local_hashes lh ON lh.hash = uh.hash
            LEFT JOIN present_remote pr ON pr.hash = uh.hash
            GROUP BY uh.row_id
        )
        SELECT v.id AS id,
            COALESCE(NULLIF(tt.sortKey,''), v.id) AS timelineKey,
            CASE WHEN tt.hash IS NOT NULL THEN tt.at ELSE v.sort_at END AS timelineAt,
            CASE WHEN tt.hash IS NOT NULL THEN tt.source ELSE v.sort_source END AS timelineSource,
            CASE
                WHEN us.row_id IS NULL THEN
                    CASE WHEN v.source_type = 'server' THEN 'REMOTE_ONLY' ELSE 'LOCAL_ONLY' END
                WHEN us.any_missing_remote = 1 THEN 'LOCAL_ONLY'
                WHEN us.any_missing_local = 1 THEN 'REMOTE_ONLY'
                ELSE 'SYNCED'
            END AS backupState
        FROM visible v
        LEFT JOIN timeline_times tt
            ON tt.hash = v.content_hash
           AND tt.namespace = :namespace
        LEFT JOIN unit_state us
            ON us.row_id = v.id
        WHERE :filter = 'ALL' OR backupState = :filter
        ORDER BY timelineAt DESC, timelineKey ASC, id ASC
        """,
    )
    fun observeServerScopedOrder(filter: String, namespace: String): Flow<List<TimelineOrder>>

    /** 绑定服务端时的首屏窗口：同一排序只取前 [limit] 行。 */
    @Query(
        """
        WITH local_hashes AS (
            SELECT content_hash AS hash
            FROM photos_table
            WHERE :namespace != '' AND source_type != 'server' AND content_hash IS NOT NULL
            GROUP BY content_hash
        ),
        -- 远程存在的部分（FR-4）：服务端投影行的自身哈希 + 其已入库的动态部分。
        -- 服务端清单不投影动态部分，「动态部分已在服务端」只能由 live_partner_id 非空推出。
        present_remote AS (
            SELECT DISTINCT s.content_hash AS hash
            FROM photos_table s
            WHERE s.source_type = 'server' AND s.content_hash IS NOT NULL
              AND EXISTS(
                  SELECT 1 FROM server_media_projection sp
                  WHERE sp.local_photo_id = s.id AND sp.server_namespace = :namespace
              )
            UNION
            SELECT DISTINCT s.live_partner_hash AS hash
            FROM photos_table s
            WHERE s.source_type = 'server'
              AND s.live_partner_id IS NOT NULL
              AND s.live_partner_hash IS NOT NULL
              AND EXISTS(
                  SELECT 1 FROM server_media_projection sp
                  WHERE sp.local_photo_id = s.id AND sp.server_namespace = :namespace
              )
        ),
        visible AS (
            SELECT p.*
            FROM photos_table p
            WHERE p.source_type != 'server'
              AND COALESCE(p.live_role, 'none') != 'motion'
            UNION ALL
            SELECT p.*
            FROM photos_table p
            WHERE p.source_type = 'server'
              AND COALESCE(p.live_role, 'none') != 'motion'
              AND EXISTS(
                  SELECT 1 FROM server_media_projection own
                  WHERE own.local_photo_id = p.id
                    AND own.server_namespace = :namespace
              )
              AND (
                  p.content_hash IS NULL
                  OR NOT EXISTS(
                      SELECT 1 FROM local_hashes lh
                      WHERE lh.hash = p.content_hash
                  )
              )
        ),
        -- 三态按「实况整体」派生：任一部分仅本机→仅本机；无仅本机部分且任一部分仅远程→仅远程；
        -- 全部部分两端同哈希→已同步。普通媒体退化为只看自身哈希。
        unit_hashes AS (
            SELECT v.id AS row_id, v.content_hash AS hash FROM visible v WHERE v.content_hash IS NOT NULL
            UNION ALL
            SELECT v.id AS row_id, v.live_partner_hash AS hash FROM visible v WHERE v.live_partner_hash IS NOT NULL
        ),
        unit_state AS (
            SELECT uh.row_id AS row_id,
                   MAX(CASE WHEN lh.hash IS NULL THEN 1 ELSE 0 END) AS any_missing_local,
                   MAX(CASE WHEN pr.hash IS NULL THEN 1 ELSE 0 END) AS any_missing_remote
            FROM unit_hashes uh
            LEFT JOIN local_hashes lh ON lh.hash = uh.hash
            LEFT JOIN present_remote pr ON pr.hash = uh.hash
            GROUP BY uh.row_id
        )
        SELECT v.id AS id,
            COALESCE(NULLIF(tt.sortKey,''), v.id) AS timelineKey,
            CASE WHEN tt.hash IS NOT NULL THEN tt.at ELSE v.sort_at END AS timelineAt,
            CASE WHEN tt.hash IS NOT NULL THEN tt.source ELSE v.sort_source END AS timelineSource,
            CASE
                WHEN us.row_id IS NULL THEN
                    CASE WHEN v.source_type = 'server' THEN 'REMOTE_ONLY' ELSE 'LOCAL_ONLY' END
                WHEN us.any_missing_remote = 1 THEN 'LOCAL_ONLY'
                WHEN us.any_missing_local = 1 THEN 'REMOTE_ONLY'
                ELSE 'SYNCED'
            END AS backupState
        FROM visible v
        LEFT JOIN timeline_times tt
            ON tt.hash = v.content_hash
           AND tt.namespace = :namespace
        LEFT JOIN unit_state us
            ON us.row_id = v.id
        WHERE :filter = 'ALL' OR backupState = :filter
        ORDER BY timelineAt DESC, timelineKey ASC, id ASC
        LIMIT :limit
        """,
    )
    fun observeServerScopedWindow(filter: String, namespace: String, limit: Int): Flow<List<TimelineOrder>>

    /** 绑定服务端的时间线：先出首屏窗口，再补齐全量。 */
    fun observeServerScopedTimeline(filter: String, namespace: String): Flow<List<TimelinePhoto>> =
        observeServerScopedWindow(filter, namespace, TIMELINE_FIRST_SCREEN).flatMapLatest { window ->
            flow {
                emit(hydrateByIds(window))
                emit(hydrateByScan(observeServerScopedOrder(filter, namespace).first(), includeServerRows = true))
            }
        }

    // ---- 回表：把「排序」与「取整行」接起来 ----

    /** 首屏回表：按主键定位。行数小，走主键索引最快。 */
    private suspend fun hydrateByIds(order: List<TimelineOrder>): List<TimelinePhoto> {
        if (order.isEmpty()) return emptyList()
        val byId = order.map { it.id }
            .chunked(SQLITE_MAX_BIND_ARGS)
            .flatMap { getByIds(it) }
            .associateBy { it.id }
        return order.mapNotNull { row -> byId[row.id]?.let(row::toTimelinePhoto) }
    }

    /**
     * 全量回表：整表顺序扫描后在内存按有序 id 重排。
     *
     * 不复用 [hydrateByIds] 是因为十万次主键定位会退化成随机读：真机实测按主键分块回表
     * 4617ms，而顺序扫描整表只读一遍数据页（约 950ms）。
     */
    private suspend fun hydrateByScan(order: List<TimelineOrder>, includeServerRows: Boolean): List<TimelinePhoto> {
        if (order.isEmpty()) return emptyList()
        val rows = if (includeServerRows) getAllPhotosForTimeline() else getLocalPhotosForTimeline()
        val byId = rows.associateBy { it.id }
        return order.mapNotNull { row -> byId[row.id]?.let(row::toTimelinePhoto) }
    }

    /** 顺序扫描用：只取本机行。 */
    /** 按内容哈希取当前身份的投影行（实况整体删除要读它的 live_partner_id）。 */
    @Query(
        "SELECT * FROM photos_table WHERE source_type = 'server' AND content_hash = :hash " +
            "ORDER BY (live_partner_id IS NOT NULL) DESC, id ASC LIMIT 1",
    )
    suspend fun getServerRowByHash(hash: String): PhotoEntity?

    @Query("SELECT * FROM photos_table WHERE source_type != 'server'")
    suspend fun getLocalPhotosForTimeline(): List<PhotoEntity>

    /** 顺序扫描用：取全部行（绑定服务端时需连当前身份的投影行一起回填）。 */
    @Query("SELECT * FROM photos_table")
    suspend fun getAllPhotosForTimeline(): List<PhotoEntity>

    /**
     * 一次性取**全量**时间线（不走首屏分段）。
     *
     * 供需要完整集合且不订阅变化的调用方使用（幻灯片、查看器初始化）。
     * [observeTimeline] 是分段的，其**第一次发射只有首屏窗口**，不能拿 `.first()` 当全量。
     */
    suspend fun getTimelineOnce(filter: String, namespace: String): List<TimelinePhoto> =
        if (namespace.isEmpty()) {
            hydrateByScan(observeUnboundOrder(filter).first(), includeServerRows = false)
        } else {
            hydrateByScan(observeServerScopedOrder(filter, namespace).first(), includeServerRows = true)
        }

    @Query("SELECT * FROM photos_table ORDER BY sort_at DESC")
    suspend fun getAll(): List<PhotoEntity>

    @Query("SELECT * FROM photos_table WHERE id = :id")
    suspend fun getById(id: String): PhotoEntity?

    @Query("SELECT * FROM photos_table WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PhotoEntity>

    @Query("SELECT * FROM photos_table WHERE source_uri IN (:uris)")
    suspend fun getBySourceUris(uris: List<String>): List<PhotoEntity>

    @Query("SELECT * FROM photos_table WHERE source_type='local' AND source_uri IS NOT NULL AND id > :after ORDER BY id LIMIT :limit")
    suspend fun localScanBatch(after: String, limit: Int): List<PhotoEntity>

    @Query("SELECT * FROM photos_table WHERE source_uri = :sourceUri")
    suspend fun getBySourceUri(sourceUri: String): PhotoEntity?

    @Query("SELECT * FROM photos_table WHERE content_hash = :hash")
    suspend fun getByContentHash(hash: String): PhotoEntity?

    @Query("SELECT * FROM photos_table WHERE name = :name AND size = :size")
    suspend fun getByNameAndSize(name: String, size: Long): List<PhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: PhotoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(photos: List<PhotoEntity>)

    @Update
    suspend fun update(photo: PhotoEntity)

    @Query("DELETE FROM photos_table WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM photos_table WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * 离线/远程保留场景：本机原件已删但远程副本保留，把该行降级为「仅远程」投影行，
     * 保留 id 与既有投影关联，时间线据此显示为仅远程。
     */
    @Query(
        """
        UPDATE photos_table
        SET source_type = 'server', source_uri = NULL, thumbnail_path = NULL, storage_id = 'server'
        WHERE id = :id
        """
    )
    suspend fun convertToServerProjection(id: String)

    @Query("DELETE FROM photos_table WHERE source_type = 'local' AND source_uri LIKE 'ph://%'")
    suspend fun deleteLegacyPhPhotos(): Int

    /** 缩略图缓存文件被清后调用：置空路径让下次扫描重建，时间线也能感知状态变化。 */
    @Query("UPDATE photos_table SET thumbnail_path = NULL WHERE source_type = 'local'")
    suspend fun clearLocalThumbnailPaths(): Int

    /** 远程投影从不代表设备媒体；解绑或换账号时只删除这些行。 */
    @Query("SELECT id FROM photos_table WHERE source_type = 'server'")
    suspend fun getServerPhotoIds(): List<String>

    @Query("DELETE FROM photos_table WHERE source_type = 'server'")
    suspend fun deleteAllServerPhotos(): Int

    @Query("SELECT COUNT(*) FROM photos_table")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM photos_table WHERE content_hash IS NULL OR content_hash = ''")
    suspend fun countMissingContentHash(): Int

    @Query("SELECT * FROM photos_table WHERE source_type = 'local' AND source_uri IS NOT NULL AND (content_hash IS NULL OR content_hash = '') AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun getMissingLocalHashes(afterId: String, limit: Int): List<PhotoEntity>

    // Only patch an unchanged, still-existing row; never overwrite concurrent favorites or resurrect deletions.
    @Query("UPDATE photos_table SET content_hash = :hash WHERE id = :id AND source_type = 'local' AND source_uri = :uri AND modified_at IS :modifiedAt AND size IS :size AND (content_hash IS NULL OR content_hash = '')")
    suspend fun fillContentHash(id: String, uri: String, modifiedAt: Long?, size: Long?, hash: String): Int

    @Query("SELECT p.* FROM photos_table p INNER JOIN server_media_projection sp ON p.id = sp.local_photo_id WHERE sp.server_namespace = :namespace AND sp.server_media_id = :serverMediaId")
    suspend fun getByServerMediaId(namespace: String, serverMediaId: String): PhotoEntity?

    @Query("DELETE FROM photos_table WHERE id IN (SELECT local_photo_id FROM server_media_projection WHERE server_namespace = :namespace AND server_media_id = :serverMediaId)")
    suspend fun deleteServerProjection(namespace: String, serverMediaId: String)

    @Query("UPDATE photos_table SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean)

    // 收藏的照片：服务端行同样受当前身份作用域约束（未绑定时不展示服务端收藏）
    @Query(
        """
        SELECT * FROM photos_table p
        WHERE p.is_favorite = 1
          AND (
              p.source_type != 'server'
              OR EXISTS(
                  SELECT 1 FROM server_media_projection sp
                  WHERE sp.local_photo_id = p.id
                    AND sp.server_namespace = :namespace
              )
          )
        ORDER BY p.sort_at DESC
        """,
    )
    fun observeFavorites(namespace: String): Flow<List<PhotoEntity>>

    /** 换绑后清掉旧身份的投影行（`source_type='server'`），本机索引不动。 */
    @Query(
        """
        DELETE FROM photos_table
        WHERE id IN (
            SELECT local_photo_id FROM server_media_projection
            WHERE server_namespace != :namespace
        )
        """,
    )
    suspend fun deleteServerPhotosOutsideNamespace(namespace: String): Int

    // 视频
    @Query("SELECT * FROM photos_table WHERE is_video = 1 ORDER BY sort_at DESC")
    suspend fun getVideos(): List<PhotoEntity>
}
