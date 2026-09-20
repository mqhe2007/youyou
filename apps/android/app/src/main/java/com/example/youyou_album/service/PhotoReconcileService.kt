package com.example.youyou_album.service

import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.repository.PhotoRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照片核对服务：全量核对本地照片的完整性和一致性。
 * 检查 contentHash、size、sourceUri 等字段，生成核对报告。
 */
@Singleton
class PhotoReconcileService @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val contentHashService: ContentHashService,
) {

    data class ReconcileReport(
        val totalPhotos: Int = 0,
        val missingContentHash: Int = 0,
        val missingSourceUri: Int = 0,
        val missingSize: Int = 0,
        val hashMismatch: Int = 0,
        val fixedCount: Int = 0,
        val failedCount: Int = 0,
    )

    /**
     * 全量核对所有照片，检查缺失字段和哈希一致性。
     * @param autoFix 是否自动修复（回填缺失的 contentHash）
     */
    suspend fun reconcileAll(autoFix: Boolean = true): ReconcileReport {
        val photos = photoRepository.getAll()
        var missingContentHash = 0
        var missingSourceUri = 0
        var missingSize = 0
        var hashMismatch = 0
        var fixedCount = 0
        var failedCount = 0

        for (photo in photos) {
            // 检查缺失字段
            if (photo.contentHash.isNullOrEmpty()) {
                missingContentHash++
                if (autoFix && photo.sourceUri != null) {
                    try {
                        val uri = android.net.Uri.parse(photo.sourceUri)
                        val hash = contentHashService.sha256HexForUri(uri)
                        if (hash != null) {
                            photoRepository.upsert(photo.copy(contentHash = hash))
                            fixedCount++
                        } else {
                            failedCount++
                        }
                    } catch (_: Exception) {
                        failedCount++
                    }
                }
            }

            if (photo.sourceUri.isNullOrEmpty()) {
                missingSourceUri++
            }

            if (photo.size == null || photo.size == 0L) {
                missingSize++
            }

            // 校验已有 contentHash 的一致性（仅对本地照片）
            if (!photo.contentHash.isNullOrEmpty() && photo.sourceUri != null && photo.sourceType == "local") {
                try {
                    val uri = android.net.Uri.parse(photo.sourceUri)
                    val actualHash = contentHashService.sha256HexForUri(uri)
                    if (actualHash != null && actualHash != photo.contentHash) {
                        hashMismatch++
                        if (autoFix) {
                            photoRepository.upsert(photo.copy(contentHash = actualHash))
                            fixedCount++
                        }
                    }
                } catch (_: Exception) {
                    // 跳过无法读取的照片
                }
            }
        }

        return ReconcileReport(
            totalPhotos = photos.size,
            missingContentHash = missingContentHash,
            missingSourceUri = missingSourceUri,
            missingSize = missingSize,
            hashMismatch = hashMismatch,
            fixedCount = fixedCount,
            failedCount = failedCount,
        )
    }

    /**
     * 核对单张照片的完整性。
     */
    suspend fun reconcilePhoto(photoId: String): ReconcileReport {
        val photo = photoRepository.getById(photoId) ?: return ReconcileReport()
        return reconcileAll(autoFix = true).copy(totalPhotos = 1)
    }
}
