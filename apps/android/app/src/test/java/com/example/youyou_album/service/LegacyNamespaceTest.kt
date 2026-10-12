package com.example.youyou_album.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * 命名空间升级迁移的判定：只对「确实由旧算法（仅地址哈希）」产生的命名空间
 * 执行改名，避免把其他身份的数据错误迁移。
 */
class LegacyNamespaceTest {
    private val sha256Hex: (String) -> String = { input ->
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun legacyNamespace(baseUrl: String): String =
        "server_" + sha256Hex(baseUrl).substring(0, 16)

    private fun identityNamespace(baseUrl: String, instanceId: String, userId: String): String =
        "server_" + sha256Hex("$baseUrl\n$instanceId\n$userId").substring(0, 16)

    @Test
    fun matchesLegacyDerivationOfTheSameAddress() {
        val base = "http://10.0.2.2:8989"
        assertTrue(isLegacyStyleNamespace(legacyNamespace(base), base, sha256Hex))
        // 旧代码对带尾斜杠写法的地址同样按原文哈希过。
        assertTrue(isLegacyStyleNamespace(legacyNamespace("$base/"), "$base/", sha256Hex))
    }

    @Test
    fun rejectsIdentityAwareOrForeignNamespaces() {
        val base = "http://10.0.2.2:8989"
        assertFalse(
            "身份化命名空间不能被当成旧式迁移",
            isLegacyStyleNamespace(identityNamespace(base, "instance-1", "7"), base, sha256Hex),
        )
        assertFalse(isLegacyStyleNamespace(legacyNamespace("http://other:8989"), base, sha256Hex))
        assertFalse(isLegacyStyleNamespace("server_not-a-hash", base, sha256Hex))
    }
}
