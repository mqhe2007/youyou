package com.example.youyou_album.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 前台服务：用于媒体扫描、服务端同步等长时任务。
 * Phase 2 实现具体逻辑。
 */
class TaskForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }
}
