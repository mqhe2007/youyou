package com.example.youyou_album.presentation.common

/**
 * 服务端可达性。首页的信任条与连接管理的状态头共用同一套状态，
 * 二者都由「进入前台 / 进入页面时的一次健康检查」驱动。
 */
enum class ServerReachability {
    UNBOUND,
    CHECKING,
    ONLINE,
    OFFLINE,
}

/**
 * 上次同步的**纯时长**表述，供带标签的指标位使用（标签已写明「上次同步」）。
 */
internal fun formatSyncedAgo(timestamp: Long?, now: Long = System.currentTimeMillis()): String = when {
    timestamp == null -> "尚未同步"
    now - timestamp < 60_000 -> "刚刚"
    now - timestamp < 3_600_000 -> "${(now - timestamp) / 60_000} 分钟前"
    now - timestamp < 86_400_000 -> "${(now - timestamp) / 3_600_000} 小时前"
    else -> "${(now - timestamp) / 86_400_000} 天前"
}

/**
 * 上次同步的**带动作**表述，供首页提示行等需要成句的位置使用（「3 分钟前同步」）。
 */
internal fun formatLastSyncedAt(timestamp: Long?, now: Long = System.currentTimeMillis()): String = when {
    timestamp == null -> "尚未完成同步"
    else -> formatSyncedAgo(timestamp, now) + "同步"
}
