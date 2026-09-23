package com.example.youyou_album.domain.model

/**
 * 实况动态部分的播放来源（需求 QI-8Xs1ejZaQ / FR-3，D4：长按或点按标记触发）。
 *
 * `requiresAuth` 为 true 表示远程内容：ExoPlayer 不走 OkHttp 拦截器，必须显式注入令牌；
 * 本机 `content://` 不附带服务端令牌。
 */
data class LiveMotionSource(
    val uri: String,
    val requiresAuth: Boolean,
)

/**
 * 解析一段实况的动态部分来源。
 *
 * 优先级：单文件动态照片直接用本文件（内嵌动态）→ 本机动态原件（按对手内容哈希找）
 * → 远程动态部分（按对手媒体 id 取内容）→ 都不可得时返回 `null`，UI 保持静态封面并
 * 给出可理解状态，不猜不伪造。
 *
 * @param localUriByHash 按内容哈希找本机原件地址
 * @param remoteContentUrlFor 按服务端媒体 id 构造远程内容地址
 * @param localUriById 按本机配对 id 找本机原件地址（内容哈希可能尚未回填，配对 id 一定有）
 */
fun resolveLiveMotionSource(
    photo: Photo,
    localUriByHash: (String) -> String?,
    remoteContentUrlFor: (String) -> String?,
    localUriById: (String) -> String? = { null },
): LiveMotionSource? {
    val live = photo.livePhoto ?: return null
    // 单文件动态照片：动态部分内嵌在同一文件里，没有独立的动态文件。
    if (live.embedded) {
        photo.sourceUri?.let { return LiveMotionSource(it, requiresAuth = false) }
        photo.remoteContentUrl?.let { return LiveMotionSource(it, requiresAuth = true) }
        return null
    }
    // 成对实况：动态部分是独立文件，本机原件优先（离线也能播放）。
    // 本机扫描行的内容哈希由后台回填，可能尚未就绪，因此配对 id 也要走一次本机查找。
    live.partnerContentHash?.let { hash ->
        localUriByHash(hash)?.let { return LiveMotionSource(it, requiresAuth = false) }
    }
    live.partnerMediaId?.let { id ->
        localUriById(id)?.let { return LiveMotionSource(it, requiresAuth = false) }
    }
    val partnerId = live.partnerMediaId ?: return null
    return remoteContentUrlFor(partnerId)?.let { LiveMotionSource(it, requiresAuth = true) }
}
