package com.example.youyou_album.presentation.photo

import android.net.Uri
import com.example.youyou_album.domain.model.Photo
import java.io.File

/** 网格 tile 的缩略图加载模型。 */
fun photoThumbnailModel(photo: Photo): Any? {
    val thumbPath = photo.thumbnailPath
    return when {
        !thumbPath.isNullOrEmpty() && File(thumbPath).exists() -> thumbPath
        // 视频 content URI 无法被 Coil 解码，缓存缺失时只能退回远程缩略图，否则占位
        photo.isVideo -> photo.remoteThumbnailUrl
        photo.sourceUri != null -> Uri.parse(photo.sourceUri)
        else -> photo.remoteThumbnailUrl
    }
}

/** 详情大图的加载模型：优先本机原件，其次远程内容，最后退到缩略图。 */
fun photoDetailModel(photo: Photo): Any? = when {
    photo.isVideo -> photo.sourceUri ?: photo.remoteContentUrl
    photo.sourceUri != null -> Uri.parse(photo.sourceUri)
    photo.remoteContentUrl != null -> photo.remoteContentUrl
    photo.remoteThumbnailUrl != null -> photo.remoteThumbnailUrl
    photo.thumbnailPath != null -> photo.thumbnailPath
    else -> null
}
