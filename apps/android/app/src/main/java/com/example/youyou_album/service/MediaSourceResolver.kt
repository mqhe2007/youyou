package com.example.youyou_album.service

import android.net.Uri
import com.example.youyou_album.domain.model.Photo
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体源解析器：统一解析本地/远程媒体路径。
 * 根据 Photo 的 sourceType 和 sourceUri/path，解析出可加载的 Uri 或文件路径。
 */
@Singleton
class MediaSourceResolver @Inject constructor() {

    sealed interface ResolvedSource {
        data class LocalUri(val uri: Uri) : ResolvedSource
        data class LocalPath(val path: String) : ResolvedSource
        data class RemoteUrl(val url: String) : ResolvedSource
        data class ContentHash(val hash: String) : ResolvedSource
        object Unknown : ResolvedSource
    }

    /**
     * 解析 Photo 的媒体源，优先使用 thumbnailPath，其次 sourceUri，最后 path。
     */
    fun resolve(photo: Photo): ResolvedSource {
        // 优先使用缩略图路径（本地缓存）；文件被系统清掉时跳过
        photo.thumbnailPath?.let { path ->
            if (path.isNotEmpty() && File(path).exists()) {
                return ResolvedSource.LocalPath(path)
            }
        }

        // 其次使用 sourceUri
        photo.sourceUri?.let { uriString ->
            return when {
                uriString.startsWith("content://") -> ResolvedSource.LocalUri(Uri.parse(uriString))
                uriString.startsWith("file://") -> ResolvedSource.LocalPath(Uri.parse(uriString).path ?: uriString)
                uriString.startsWith("http://") || uriString.startsWith("https://") -> ResolvedSource.RemoteUrl(uriString)
                else -> ResolvedSource.LocalPath(uriString)
            }
        }

        // 最后使用 path
        if (photo.path.isNotEmpty()) {
            return when {
                photo.path.startsWith("content://") -> ResolvedSource.LocalUri(Uri.parse(photo.path))
                photo.path.startsWith("http://") || photo.path.startsWith("https://") -> ResolvedSource.RemoteUrl(photo.path)
                else -> ResolvedSource.LocalPath(photo.path)
            }
        }

        // 使用 contentHash 作为标识
        photo.contentHash?.let { hash ->
            return ResolvedSource.ContentHash(hash)
        }

        return ResolvedSource.Unknown
    }

    /**
     * 获取可用于 Coil/AsyncImage 加载的 model（Uri 或 String 或 File）。
     */
    fun toLoadModel(photo: Photo): Any? {
        return when (val source = resolve(photo)) {
            is ResolvedSource.LocalUri -> source.uri
            is ResolvedSource.LocalPath -> source.path
            is ResolvedSource.RemoteUrl -> source.url
            is ResolvedSource.ContentHash -> null // 需要通过服务端 API 获取
            is ResolvedSource.Unknown -> null
        }
    }

    /**
     * 判断是否为本地媒体。
     */
    fun isLocal(photo: Photo): Boolean {
        return photo.sourceType == "local" || photo.sourceUri?.startsWith("content://") == true
    }

    /**
     * 判断是否为远程媒体（服务端）。
     */
    fun isRemote(photo: Photo): Boolean {
        return photo.sourceType == "remote" || photo.storageId != null
    }
}
