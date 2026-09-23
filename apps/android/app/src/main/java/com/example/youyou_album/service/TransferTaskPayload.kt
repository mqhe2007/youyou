package com.example.youyou_album.service

import com.example.youyou_album.domain.model.ServerConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A persisted batch contains no token. The device id prevents replay under another account. */
@Serializable
data class TransferTaskPayload(
    val identity: String,
    val items: List<TransferTaskItem>,
) {
    val succeeded: Int get() = items.count { it.status == "succeeded" }
    val failed: Int get() = items.count { it.status == "failed" }
    val unfinished: Int get() = items.count { it.status == "pending" }
    val cancelled: Int get() = items.count { it.status == "cancelled" }
    val retryIds: Set<String> get() = items.filter { it.status == "failed" || it.status == "pending" }.map { it.photoId }.toSet()

    fun update(photoId: String, status: String, message: String? = null): TransferTaskPayload =
        copy(items = items.map { if (it.photoId == photoId) it.copy(status = status, message = message) else it })

    fun recordHash(photoId: String, hash: String): TransferTaskPayload =
        copy(items = items.map { if (it.photoId == photoId) it.copy(contentHash = hash) else it })

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun read(raw: String?): TransferTaskPayload? = raw?.let {
            runCatching { json.decodeFromString<TransferTaskPayload>(it) }.getOrNull()
        }

        fun write(payload: TransferTaskPayload): String = json.encodeToString(payload)

        fun identity(connection: ServerConnection?, deviceId: String?): String? =
            if (connection == null || connection.serverInstanceId.isNullOrBlank() || deviceId.isNullOrBlank()) null
            else "${connection.baseUrl}\n${connection.serverInstanceId}\n$deviceId"
    }
}

@Serializable
data class TransferTaskItem(
    val photoId: String,
    val name: String = photoId,
    val sourceUri: String? = null,
    val contentHash: String? = null,
    val status: String = "pending",
    val message: String? = null,
)
