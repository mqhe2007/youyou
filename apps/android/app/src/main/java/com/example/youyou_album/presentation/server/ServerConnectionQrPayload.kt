package com.example.youyou_album.presentation.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 管理端生成的服务端连接二维码载荷。
 * 二维码只包含当前服务端地址和一次性配对码，不保存设备令牌。
 */
data class ServerConnectionQrPayload(
    val serverUrl: String,
    val pairingCode: String,
) {
    @Serializable
    private data class QrJson(
        val type: String,
        val version: Int,
        @SerialName("serverUrl") val serverUrl: String,
        @SerialName("pairingCode") val pairingCode: String,
    )

    companion object {
        private const val TYPE = "youyou-connect"
        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }

        fun fromRawValue(rawValue: String): ServerConnectionQrPayload {
            val raw = rawValue.trim()
            require(raw.isNotEmpty()) { "二维码内容为空" }

            val decoded = try {
                json.decodeFromString(QrJson.serializer(), raw)
            } catch (_: Exception) {
                throw IllegalArgumentException("请扫描管理端生成的连接二维码")
            }

            require(decoded.type == TYPE && decoded.version == VERSION) {
                "请扫描管理端生成的连接二维码"
            }
            require(decoded.serverUrl.isNotBlank() && decoded.pairingCode.isNotBlank()) {
                "二维码信息不完整，请重新生成"
            }

            // 校验 URL 格式
            val uri = java.net.URI.create(decoded.serverUrl.trim())
            require(uri.scheme == "http" || uri.scheme == "https") {
                "二维码中的服务端地址无效"
            }
            require(uri.host?.isNotBlank() == true) {
                "二维码中的服务端地址无效"
            }

            return ServerConnectionQrPayload(
                serverUrl = decoded.serverUrl.trim().trimEnd('/'),
                pairingCode = decoded.pairingCode.trim(),
            )
        }
    }
}
