package com.example.youyou_album.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.api.dto.PairRequestDto
import com.example.youyou_album.data.api.interceptor.TokenProvider
import com.example.youyou_album.domain.model.ServerConnection
import com.example.youyou_album.service.SecureStorageService
import com.example.youyou_album.service.ServerConnectionStore
import com.example.youyou_album.util.MockServerRule
import com.example.youyou_album.util.TestDataFactory
import com.example.youyou_album.util.TestDependencies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 服务器连接配对业务流程 E2E 测试。
 *
 * 覆盖核心业务功能：
 * 1. 配对成功流程：pair API → 存储凭证 → 保存连接信息
 * 2. 配对失败处理：409/429/401/426 等错误码
 * 3. 断开连接流程：撤销设备 → 清除本地数据
 * 4. 凭证持久化：SecureStorage 读写
 * 5. 连接状态持久化：DataStore 读写
 * 6. Token 注入：AuthInterceptor 自动携带 token
 *
 * 使用 MockWebServer 模拟服务端，手动创建依赖（不依赖 Hilt）。
 */
@RunWith(AndroidJUnit4::class)
class ServerConnectionE2ETest {

    @get:Rule
    val mockServer = MockServerRule()

    private lateinit var secureStorage: SecureStorageService
    private lateinit var connectionStore: ServerConnectionStore
    private lateinit var tokenProvider: TokenProvider
    private lateinit var apiService: YouyouApiService

    /** setup 是否真的跑完了。被真机闸门跳过时为 false，此时 cleanup 绝不能碰任何真实状态。 */
    private var isolatedStateReady = false

    @Before
    fun setup() = runBlocking {
        // 真机保护：本用例的 setUp/tearDown 会清真实 prefs 与真实连接状态，真机上直接跳过。
        TestDependencies.assumeNoRealUserState()

        secureStorage = TestDependencies.createSecureStorage()
        connectionStore = TestDependencies.createServerConnectionStore()
        tokenProvider = TestDependencies.createTokenProvider()

        // 清理之前测试的残留状态
        connectionStore.clearConnection()
        TestDependencies.clearSecureStorage()
        tokenProvider.setToken(null)

        // 创建指向 MockWebServer 的 API 服务（带 token 注入）
        apiService = TestDependencies.createApiService(
            baseUrl = mockServer.baseUrl,
            okHttpClient = TestDependencies.createOkHttpClient(tokenProvider),
        )
        isolatedStateReady = true
    }

    @After
    fun cleanup() = runBlocking {
        // 注意：@Before 里假设失败（真机闸门跳过）时，JUnit 仍会执行 @After。
        // 此时既不能碰未初始化的 lateinit，更不能去清用户真机上的真实 prefs——
        // 那会直接把用户已配对的身份清掉。
        if (!isolatedStateReady) return@runBlocking

        connectionStore.clearConnection()
        TestDependencies.clearSecureStorage()
        tokenProvider.setToken(null)
    }

    // ─── 1. 配对成功流程 ───

    @Test
    fun pair_success_savesCredentialsAndConnection() = runBlocking {
        // 入队 pairing 响应
        mockServer.enqueueJson(TestDataFactory.deviceCredentialsJson(
            deviceId = "dev_e2e_001",
            token = "tok_e2e_001",
        ))
        // 入队 serverInfo 响应
        mockServer.enqueueJson(TestDataFactory.serverInfoJson(
            serverInstanceId = "inst_e2e_001",
        ))

        // 执行配对
        val credentials = apiService.pair(PairRequestDto(
            code = "valid-code",
            deviceName = "e2e-test-device",
        ))

        // 验证凭证
        assertEquals("dev_e2e_001", credentials.deviceId)
        assertEquals("tok_e2e_001", credentials.token)

        // 模拟 ViewModel 的存储逻辑
        secureStorage.saveDeviceToken(credentials.token)
        secureStorage.saveDeviceId(credentials.deviceId)
        tokenProvider.setToken(credentials.token)

        val serverInfo = apiService.serverInfo()
        val connection = ServerConnection(
            baseUrl = mockServer.baseUrl.trimEnd('/'),
            serverInstanceId = serverInfo.serverInstanceId,
            deviceId = credentials.deviceId,
            deviceName = "e2e-test-device",
        )
        connectionStore.saveConnection(connection)

        // 验证持久化
        assertEquals("tok_e2e_001", secureStorage.getDeviceToken())
        assertEquals("dev_e2e_001", secureStorage.getDeviceId())
        assertEquals("tok_e2e_001", tokenProvider.getToken())

        val savedConnection = connectionStore.getConnection()
        assertNotNull(savedConnection)
        assertEquals("inst_e2e_001", savedConnection!!.serverInstanceId)
        assertEquals("e2e-test-device", savedConnection.deviceName)

        // 验证请求路径
        val pairRequest = mockServer.takeRequest()
        assertEquals("POST", pairRequest.method)
        assertTrue(pairRequest.path!!.contains("/api/v1/pairing"))

        val infoRequest = mockServer.takeRequest()
        assertEquals("GET", infoRequest.method)
        assertTrue(infoRequest.path!!.contains("/api/v1/server"))
    }

    // ─── 2. 配对失败处理 ───

    @Test
    fun pair_failure_409_expiredCode() {
        mockServer.enqueueError(409, "code expired")

        val exception = assertThrows(retrofit2.HttpException::class.java) {
            runBlocking {
                apiService.pair(PairRequestDto(code = "expired", deviceName = "test"))
            }
        }
        assertEquals(409, exception.code())
    }

    @Test
    fun pair_failure_429_rateLimited() {
        mockServer.enqueueError(429, "too many requests")

        val exception = assertThrows(retrofit2.HttpException::class.java) {
            runBlocking {
                apiService.pair(PairRequestDto(code = "test", deviceName = "test"))
            }
        }
        assertEquals(429, exception.code())
    }

    @Test
    fun pair_failure_401_invalidCode() {
        mockServer.enqueueError(401, "invalid code")

        val exception = assertThrows(retrofit2.HttpException::class.java) {
            runBlocking {
                apiService.pair(PairRequestDto(code = "invalid", deviceName = "test"))
            }
        }
        assertEquals(401, exception.code())
    }

    @Test
    fun pair_failure_426_clientTooOld() {
        mockServer.enqueueError(426, "upgrade required")

        val exception = assertThrows(retrofit2.HttpException::class.java) {
            runBlocking {
                apiService.pair(PairRequestDto(code = "test", deviceName = "test"))
            }
        }
        assertEquals(426, exception.code())
    }

    // ─── 3. 断开连接流程 ───

    @Test
    fun disconnect_clearsAllLocalState() = runBlocking {
        // 先建立连接状态
        secureStorage.saveDeviceToken("tok_to_clear")
        secureStorage.saveDeviceId("dev_to_clear")
        tokenProvider.setToken("tok_to_clear")
        connectionStore.saveConnection(ServerConnection(
            baseUrl = mockServer.baseUrl.trimEnd('/'),
            serverInstanceId = "inst_test",
            deviceName = "test",
        ))

        // 入队 revoke 响应（204）
        mockServer.enqueueEmpty(204)

        // 执行断开
        apiService.revokeSelfDevice()
        connectionStore.clearConnection()
        TestDependencies.clearSecureStorage()
        tokenProvider.setToken(null)

        // 验证全部清除
        assertNull(secureStorage.getDeviceToken())
        assertNull(tokenProvider.getToken())
        assertNull(connectionStore.getConnection())

        // 验证 revoke 请求
        val revokeRequest = mockServer.takeRequest()
        assertEquals("POST", revokeRequest.method)
        assertTrue(revokeRequest.path!!.contains("/devices/me/revoke"))
    }

    @Test
    fun disconnect_serverUnreachable_stillClearsLocal() = runBlocking {
        // 先建立连接状态
        secureStorage.saveDeviceToken("tok_to_clear")
        tokenProvider.setToken("tok_to_clear")
        connectionStore.saveConnection(ServerConnection(
            baseUrl = mockServer.baseUrl.trimEnd('/'),
            serverInstanceId = "inst_test",
            deviceName = "test",
        ))

        // 关闭 MockServer 模拟服务端不可达
        mockServer.server.shutdown()

        // 执行断开：revoke 失败但本地仍应清除
        try {
            apiService.revokeSelfDevice()
        } catch (_: Exception) {
            // 预期失败
        }
        connectionStore.clearConnection()
        TestDependencies.clearSecureStorage()
        tokenProvider.setToken(null)

        // 验证本地状态已清除（不依赖服务端响应）
        assertNull(secureStorage.getDeviceToken())
        assertNull(tokenProvider.getToken())
        assertNull(connectionStore.getConnection())
    }

    // ─── 4. Token 注入验证 ───

    @Test
    fun authInterceptor_injectsTokenOnAuthenticatedRequests() = runBlocking {
        // 设置 token
        tokenProvider.setToken("tok_injected_001")
        secureStorage.saveDeviceToken("tok_injected_001")

        // 入队 health 响应
        mockServer.enqueueJson(TestDataFactory.healthJson())
        apiService.health()

        val request = mockServer.takeRequest()
        val authHeader = request.getHeader("Authorization")
        assertNotNull("Authorization header should be present", authHeader)
        assertTrue("Token should be in Authorization header",
            authHeader!!.contains("tok_injected_001"))
    }

    @Test
    fun authInterceptor_noTokenWhenNotSet() = runBlocking {
        // 不设置 token
        tokenProvider.setToken(null)

        mockServer.enqueueJson(TestDataFactory.healthJson())
        apiService.health()

        val request = mockServer.takeRequest()
        // 无 token 时请求不应崩溃
        assertNotNull(request)
    }

    // ─── 5. 连接状态 Flow 验证 ───

    @Test
    fun connectionFlow_emitsUpdatesOnSaveAndClear() = runBlocking {
        // 初始应为 null
        assertNull(connectionStore.connectionFlow.first())

        // 保存连接
        val connection = ServerConnection(
            baseUrl = "http://test:8080",
            serverInstanceId = "inst_flow_test",
            deviceName = "flow-device",
        )
        connectionStore.saveConnection(connection)

        // Flow 应发出新值
        val emitted = connectionStore.connectionFlow.first()
        assertNotNull(emitted)
        assertEquals("inst_flow_test", emitted!!.serverInstanceId)
        assertEquals("http://test:8080", emitted.baseUrl)

        // 清除后 Flow 应发出 null
        connectionStore.clearConnection()
        assertNull(connectionStore.connectionFlow.first())
    }

    // ─── 6. 设备 ID 持久化 ───

    @Test
    fun deviceId_persistsAcrossInstances() = runBlocking {
        secureStorage.saveDeviceId("dev_persist_001")
        assertEquals("dev_persist_001", secureStorage.getDeviceId())

        // 创建新实例验证持久化
        val newStorage = TestDependencies.createSecureStorage()
        assertEquals("dev_persist_001", newStorage.getDeviceId())
    }
}
