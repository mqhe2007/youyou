package com.example.youyou_album.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * 通知渠道管理。支持范围内必须为每个通知指定渠道。
 */
object NotificationChannels {
    const val CHANNEL_UPLOAD = "upload_progress"
    const val CHANNEL_SCAN = "media_scan"
    const val CHANNEL_DOWNLOAD = "download_export"
    const val CHANNEL_GENERAL = "general"

    fun createAll(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 上传进度渠道
        val uploadChannel = NotificationChannel(
            CHANNEL_UPLOAD,
            "上传进度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示照片上传到服务端的进度"
            setShowBadge(false)
        }

        // 媒体扫描渠道
        val scanChannel = NotificationChannel(
            CHANNEL_SCAN,
            "媒体扫描",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示媒体库扫描进度"
            setShowBadge(false)
        }

        // 下载/导出渠道
        val downloadChannel = NotificationChannel(
            CHANNEL_DOWNLOAD,
            "下载导出",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "显示服务端照片下载和导出进度"
        }

        // 通用通知渠道
        val generalChannel = NotificationChannel(
            CHANNEL_GENERAL,
            "其他通知",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "其他应用通知"
        }

        notificationManager.createNotificationChannels(
            listOf(uploadChannel, scanChannel, downloadChannel, generalChannel)
        )
    }
}
