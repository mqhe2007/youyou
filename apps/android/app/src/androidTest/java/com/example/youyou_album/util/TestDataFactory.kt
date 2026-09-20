package com.example.youyou_album.util

import com.example.youyou_album.data.api.dto.*

/**
 * 测试数据工厂：生成各 DTO 的测试实例和 JSON 字符串。
 * 所有测试用例统一使用这里的数据，保证一致性。
 */
object TestDataFactory {

    // ─── Health & Setup ───

    fun healthJson(status: String = "ok", service: String = "youyou-server") =
        """{"status":"$status","service":"$service"}"""

    fun setupStatusJson(initialized: Boolean = true) =
        """{"initialized":$initialized}"""

    // ─── Pairing ───

    fun pairRequestJson(code: String = "test-code-123", deviceName: String = "test-device") =
        """{"code":"$code","deviceName":"$deviceName"}"""

    fun deviceCredentialsJson(
        deviceId: String = "dev_abc123",
        token: String = "tok_xyz789",
    ) = """{"deviceId":"$deviceId","token":"$token"}"""

    // ─── Server Info ───

    fun serverInfoJson(
        serverVersion: String = "0.2.0",
        apiVersion: String = "v1",
        minClientVersion: String = "0.1.0",
        serverInstanceId: String = "inst_test001",
        storageDriver: String = "local_filesystem",
        writable: Boolean = true,
    ) = """
        {
          "serverVersion":"$serverVersion",
          "apiVersion":"$apiVersion",
          "minClientVersion":"$minClientVersion",
          "serverInstanceId":"$serverInstanceId",
          "capabilities":{"storageDriver":"$storageDriver"},
          "storage":{"readOnly":${!writable},"writable":$writable}
        }
    """.trimIndent()

    // ─── Media ───

    fun serverMediaDtoJson(
        id: String = "media_001",
        name: String = "photo.jpg",
        path: String = "/photos/photo.jpg",
        size: Long = 1024000,
        mimeType: String = "image/jpeg",
        isVideo: Boolean = false,
        width: Int = 1920,
        height: Int = 1080,
        takenAt: Long = 1700000000000L,
        version: Int = 1,
    ) = """
        {
          "id":"$id",
          "name":"$name",
          "path":"$path",
          "storageId":"stor_001",
          "size":$size,
          "mimeType":"$mimeType",
          "isVideo":$isVideo,
          "width":$width,
          "height":$height,
          "contentHash":"sha256_abc",
          "identityState":"confirmed",
          "hashState":"confirmed",
          "takenAt":$takenAt,
          "sortAt":$takenAt,
          "version":$version
        }
    """.trimIndent()

    fun mediaPageJson(itemsJson: String = "", hasMore: Boolean = false, nextCursor: String? = null) =
        """{"items":[$itemsJson],"nextCursor":${nextCursor?.let { "\"$it\"" } ?: "null"},"hasMore":$hasMore}"""

    // ─── Tags ───

    fun serverTagDtoJson(
        id: String = "tag_001",
        name: String = "Test Tag",
        photoCount: Int = 3,
        version: Int = 1,
    ) = """
        {
          "id":"$id",
          "name":"$name",
          "photoCount":$photoCount,
          "version":$version,
          "createdAt":1700000000000,
          "updatedAt":1700000000000
        }
    """.trimIndent()

    fun tagPageJson(itemsJson: String = "", hasMore: Boolean = false) =
        """{"items":[$itemsJson],"nextCursor":null,"hasMore":$hasMore}"""

    // ─── Sync / Bootstrap ───

    fun bootstrapStartJson(snapshotId: String = "snap_001", jobId: String = "job_001") =
        """{"snapshotId":"$snapshotId","jobId":"$jobId","state":"preparing"}"""

    fun bootstrapStatusJson(
        snapshotId: String = "snap_001",
        state: String = "completed",
        jobId: String = "job_001",
        serverVersion: String = "0.2.0",
    ) = """
        {
          "serverVersion":"$serverVersion",
          "snapshotId":"$snapshotId",
          "jobId":"$jobId",
          "state":"$state",
          "snapshotRevision":1,
          "expiresAt":0,
          "changesCursor":null
        }
    """.trimIndent()

    // ─── Changes ───

    fun changesPageJson(itemsJson: String = "", hasMore: Boolean = false) =
        """{"items":[$itemsJson],"nextCursor":null,"hasMore":$hasMore}"""

    // ─── Jobs ───

    fun serverJobDtoJson(
        id: String = "job_001",
        kind: String = "scan",
        status: String = "completed",
        current: Int = 100,
        total: Int = 100,
    ) = """
        {
          "id":"$id",
          "kind":"$kind",
          "status":"$status",
          "current":$current,
          "total":$total,
          "retryCount":0,
          "maxAttempts":3,
          "cancelRequested":false
        }
    """.trimIndent()
}
