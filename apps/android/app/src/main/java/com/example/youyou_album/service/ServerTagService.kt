package com.example.youyou_album.service

import android.util.Log
import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.api.dto.CreateTagRequestDto
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.model.Tag
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TagRepository
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 服务端写操作的统一结果：成功 / 不在线（调用方按本机语义处理）/ 失败（带用户可读原因）。 */
sealed interface ServerWriteResult<out T> {
    data class Success<T>(val value: T) : ServerWriteResult<T>
    object Offline : ServerWriteResult<Nothing>
    data class Failure(val message: String) : ServerWriteResult<Nothing>
}

/**
 * 客户端侧标签写路径：把「新建 / 删除标签、从照片上移除标签」推到服务端。
 *
 * 服务端是跨设备标签的事实来源；本机标签（sourceType=local）仍然只存在于本机。
 * 之前这些操作只写 Room，另一台设备永远看不到——本服务把写路径接通，与同步引擎
 * 共用同一套「服务端实体 → 本机稳定 id」推导与命名空间。
 */
@Singleton
class ServerTagService @Inject constructor(
    private val apiServiceFactory: ApiServiceFactory,
    private val serverConnectionStore: ServerConnectionStore,
    private val serverSyncService: ServerSyncService,
    private val tagRepository: TagRepository,
    private val photoRepository: PhotoRepository,
    private val contentHashService: ContentHashService,
) {

    private companion object {
        const val TAG = "ServerTagService"
    }

    private data class OnlineContext(val service: YouyouApiService, val namespace: String)

    /** 已连接且已有生效的服务端身份时返回可写上下文；否则 null。 */
    private suspend fun onlineContext(): OnlineContext? {
        val connection = serverConnectionStore.getConnection() ?: return null
        val namespace = serverSyncService.activeServerNamespace()?.takeIf { it.isNotBlank() }
            ?: return null
        return OnlineContext(apiServiceFactory.create(connection.baseUrl), namespace)
    }

    private fun idempotencyKey(): String = UUID.randomUUID().toString()

    suspend fun createTag(name: String): ServerWriteResult<Tag> {
        val context = onlineContext() ?: return ServerWriteResult.Offline
        return try {
            val dto = context.service.createTag(
                CreateTagRequestDto(name = name),
                idempotencyKey(),
            )
            val localId = serverEntityLocalId(contentHashService, context.namespace, "tag", dto.id)
            val tag = Tag(
                id = localId,
                name = dto.name,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
                sourceType = "server",
                serverNamespace = context.namespace,
                serverRemoteId = dto.id,
                version = dto.version.toLong(),
            )
            tagRepository.upsert(tag)
            ServerWriteResult.Success(tag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            ServerWriteResult.Failure(
                when (e.code()) {
                    409 -> "同名标签已存在"
                    401, 403 -> "登录已失效，请重新连接服务端"
                    else -> "创建标签失败：HTTP ${e.code()}"
                }
            )
        } catch (e: java.io.IOException) {
            // 网络不可达按离线处理：调用方回退本机标签。
            ServerWriteResult.Offline
        } catch (e: Exception) {
            ServerWriteResult.Failure("创建标签失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 删除服务端标签（If-Match 乐观并发）；服务端已不存在按成功处理。
     * 成功后本机标签行一并删除。本机标签请由调用方直接本机删除。
     */
    suspend fun deleteTag(tag: Tag): ServerWriteResult<Unit> {
        val remoteId = tag.serverRemoteId
        if (tag.sourceType != "server" || remoteId.isNullOrBlank()) {
            return ServerWriteResult.Success(Unit)
        }
        val context = onlineContext() ?: return ServerWriteResult.Offline
        if (tag.serverNamespace != null && tag.serverNamespace != context.namespace) {
            return ServerWriteResult.Failure("标签不属于当前服务端身份，请重新同步后再试")
        }
        return try {
            context.service.deleteTag(
                id = remoteId,
                ifMatch = tag.version.toString(),
                idempotencyKey = idempotencyKey(),
            )
            tagRepository.deleteById(tag.id)
            ServerWriteResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> {
                    // 服务端已无此标签（另一台设备先删了）：本机也清理掉。
                    tagRepository.deleteById(tag.id)
                    ServerWriteResult.Success(Unit)
                }
                409 -> ServerWriteResult.Failure("标签已在其他设备变化，请刷新标签页后重试")
                401, 403 -> ServerWriteResult.Failure("登录已失效，请重新连接服务端")
                else -> ServerWriteResult.Failure("删除标签失败：HTTP ${e.code()}")
            }
        } catch (e: java.io.IOException) {
            ServerWriteResult.Offline
        } catch (e: Exception) {
            ServerWriteResult.Failure("删除标签失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 给照片添加标签。
     *
     * - 本机标签，或照片没有服务端副本：只在本机加关联（返回 Success）。
     * - 服务端标签 + 照片有服务端副本：先写服务端（幂等；已存在同关联时服务端
     *   返回既有关系），成功后把本机关联挂到服务端投影行（与同步下行的落点一致，
     *   避免同一照片在标签详情出现两块磁贴）；离线返回 [ServerWriteResult.Offline]，
     *   调用方提示用户，不产生只在本机的分叉关联。
     */
    suspend fun addTagToPhoto(tag: Tag, photo: Photo): ServerWriteResult<Unit> {
        if (tag.sourceType != "server" || tag.serverRemoteId.isNullOrBlank()) {
            tagRepository.addTagToPhoto(tag.id, photo.id)
            return ServerWriteResult.Success(Unit)
        }
        val serverMediaId = photoRepository.resolveServerMediaId(photo)
        if (serverMediaId == null) {
            tagRepository.addTagToPhoto(tag.id, photo.id)
            return ServerWriteResult.Success(Unit)
        }
        val context = onlineContext() ?: return ServerWriteResult.Offline
        if (tag.serverNamespace != null && tag.serverNamespace != context.namespace) {
            return ServerWriteResult.Failure("标签不属于当前服务端身份，请重新同步后再试")
        }
        return try {
            context.service.addTagMedia(
                tagId = tag.serverRemoteId,
                mediaId = serverMediaId,
                idempotencyKey = idempotencyKey(),
            )
            val targetRow = photoRepository.getByServerMediaId(context.namespace, serverMediaId)?.id
                ?: photo.id
            tagRepository.addTagToPhoto(tag.id, targetRow, context.namespace)
            ServerWriteResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> ServerWriteResult.Failure("照片或标签在服务端已不存在，请同步后重试")
                401, 403 -> ServerWriteResult.Failure("登录已失效，请重新连接服务端")
                else -> ServerWriteResult.Failure("添加标签失败：HTTP ${e.code()}")
            }
        } catch (e: java.io.IOException) {
            ServerWriteResult.Offline
        } catch (e: Exception) {
            ServerWriteResult.Failure("添加标签失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 从照片上移除标签。
     *
     * - 本机标签，或照片没有服务端副本：只在本机移除关联（返回 Success，调用方
     *   直接删本机关联即可——本方法也会顺手删除，调用方重复删除无副作用）。
     * - 服务端标签 + 照片有服务端副本：先删服务端关联（404 视为已删），成功后把
     *   本机两侧的关联（选中行 + 服务端投影行）一起清理；离线时返回 [ServerWriteResult.Offline]，
     *   调用方应跳过本地删除并提示，避免下次同步把关联又拉回来。
     */
    suspend fun removeTagFromPhoto(tag: Tag, photo: Photo): ServerWriteResult<Unit> {        if (tag.sourceType != "server" || tag.serverRemoteId.isNullOrBlank()) {
            tagRepository.removeTagFromPhoto(tag.id, photo.id)
            return ServerWriteResult.Success(Unit)
        }
        val serverMediaId = photoRepository.resolveServerMediaId(photo)
        if (serverMediaId == null) {
            tagRepository.removeTagFromPhoto(tag.id, photo.id)
            return ServerWriteResult.Success(Unit)
        }
        val context = onlineContext() ?: return ServerWriteResult.Offline
        if (tag.serverNamespace != null && tag.serverNamespace != context.namespace) {
            return ServerWriteResult.Failure("标签不属于当前服务端身份，请重新同步后再试")
        }
        return try {
            val response = context.service.removeTagMedia(
                tagId = tag.serverRemoteId,
                mediaId = serverMediaId,
                idempotencyKey = idempotencyKey(),
            )
            if (response.isSuccessful || response.code() == 404) {
                tagRepository.removeTagFromPhoto(tag.id, photo.id)
                // 关联也可能挂在同一媒体的服务端投影行上（时间线隐藏的那一行）。
                photoRepository.getByServerMediaId(context.namespace, serverMediaId)?.let { twin ->
                    if (twin.id != photo.id) tagRepository.removeTagFromPhoto(tag.id, twin.id)
                }
                ServerWriteResult.Success(Unit)
            } else {
                ServerWriteResult.Failure("移除标签失败：HTTP ${response.code()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            ServerWriteResult.Offline
        } catch (e: Exception) {
            Log.d(TAG, "remove tag relation failed: ${e.javaClass.simpleName}")
            ServerWriteResult.Failure("移除标签失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
