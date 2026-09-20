package com.example.youyou_album.presentation.home

import com.example.youyou_album.presentation.common.ServerReachability

/**
 * 首页顶部「连接信任条」的状态。
 *
 * `ServerReachability` 与相对时间格式化已提到 `presentation.common`，
 * 连接管理页的状态头复用同一套判定，避免两处各写一份。
 */
data class HomeTrustState(
    val reachability: ServerReachability = ServerReachability.UNBOUND,
    val lastSyncedAt: Long? = null,
)
