package com.example.youyou_album.service

import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.api.interceptor.TokenProvider
import com.example.youyou_album.data.db.dao.MediaOperationDao
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** 已确认的服务端与用户身份；令牌只留在本次内存会话，不进入数据库。 */
internal class RemoteDeletionSession(
    val baseUrl: String,
    val scope: String,
    val authorization: String,
    val generation: Long,
    val api: YouyouApiService,
)

@Singleton
class RemoteDeletionIdentity @Inject constructor(
    private val connectionStore: ServerConnectionStore,
    private val tokenProvider: TokenProvider,
    private val apiServiceFactory: ApiServiceFactory,
    private val contentHashService: ContentHashService,
    private val operationDao: MediaOperationDao,
) {
    internal suspend fun capture(): RemoteDeletionSession? {
        val connection = connectionStore.getConnection() ?: return null
        val generation = tokenProvider.generation
        val token = tokenProvider.getToken() ?: return null
        val api = apiServiceFactory.create(connection.baseUrl, mediaOperations = true)
        return try {
            val info = api.serverInfo("Bearer $token")
            val userId = info.userId?.takeIf { it.isNotBlank() } ?: return null
            if (info.serverInstanceId.isBlank()) return null
            val scope = "identity_" + contentHashService.sha256HexForString("${info.serverInstanceId.length}:${info.serverInstanceId}:$userId")
            RemoteDeletionSession(connection.baseUrl, scope, "Bearer $token", generation, api)
                .takeIf { isCurrent(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    internal suspend fun isCurrent(session: RemoteDeletionSession): Boolean =
        tokenProvider.generation == session.generation &&
            "Bearer ${tokenProvider.getToken()}" == session.authorization &&
            connectionStore.getConnection()?.baseUrl == session.baseUrl

    /** 重连只查询同一稳定身份的结果；旧URL作用域无法证明归属，保留待确认。 */
    suspend fun reconcile() {
        val pending = operationDao.listUnresolved()
        if (pending.isEmpty()) return
        val session = capture() ?: return
        for (operation in pending.filter { it.serverNamespace == session.scope }) {
            if (!isCurrent(session)) return
            try {
                val result = session.api.getMediaOperation(operation.operationId, session.authorization)
                if (!isCurrent(session)) return
                if (result.kind != "delete" || result.mediaId != operation.serverMediaId) continue
                if (result.state in setOf("succeeded", "failed", "conflict", "not_found")) {
                    operationDao.updateOperationState(operation.operationId, result.state, result.error, System.currentTimeMillis())
                }
                // 不按旧成功结果删除当前投影：媒体可能已经恢复，现状由正常同步更新。
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 404、断网等均不是成功证明，也不重放 DELETE。
            }
        }
    }
}
