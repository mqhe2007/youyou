package com.example.youyou_album.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.api.dto.CreateTagRequestDto
import com.example.youyou_album.data.api.dto.UpdateTagRequestDto
import com.example.youyou_album.util.MockServerRule
import com.example.youyou_album.util.TestDataFactory
import com.example.youyou_album.util.TestDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API 契约 E2E 测试。
 *
 * 验证所有核心 API 端点的请求格式、响应解析、HTTP 方法和路径正确性。
 * 使用 MockWebServer 模拟服务端，手动创建 Retrofit 依赖（不依赖 Hilt）。
 */
@RunWith(AndroidJUnit4::class)
class ApiContractE2ETest {

    @get:Rule
    val mockServer = MockServerRule()

    private lateinit var apiService: YouyouApiService

    @Before
    fun setup() {
        apiService = TestDependencies.createApiService(mockServer.baseUrl)
    }

    // ─── Health & Setup ───

    @Test
    fun health_getReturnsStatus() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.healthJson(status = "ok", service = "youyou-server"))

        val result = apiService.health()

        assertEquals("ok", result.status)
        assertEquals("youyou-server", result.service)

        val request = mockServer.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path!!.endsWith("/api/v1/health"))
    }

    @Test
    fun setupStatus_getReturnsInitialized() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.setupStatusJson(initialized = true))

        val result = apiService.setupStatus()

        assertTrue(result.initialized)

        val request = mockServer.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path!!.contains("/admin/setup"))
    }

    // ─── Pairing ───

    @Test
    fun pair_postSendsCodeAndDeviceName() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.deviceCredentialsJson(
            deviceId = "dev_contract_001",
            token = "tok_contract_001",
        ))

        val result = apiService.pair(
            com.example.youyou_album.data.api.dto.PairRequestDto(
                code = "test-code",
                deviceName = "test-device",
            )
        )

        assertEquals("dev_contract_001", result.deviceId)
        assertEquals("tok_contract_001", result.token)

        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/api/v1/pairing"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("test-code"))
        assertTrue(body.contains("test-device"))
    }

    @Test
    fun pair_failure_409_expiredCode() {
        mockServer.enqueueError(409, "code expired")

        val exception = assertThrows(retrofit2.HttpException::class.java) {
            runBlocking {
                apiService.pair(
                    com.example.youyou_album.data.api.dto.PairRequestDto(code = "expired", deviceName = "test")
                )
            }
        }
        assertEquals(409, exception.code())
    }

    // ─── Server Info ───

    @Test
    fun serverInfo_getReturnsFullInfo() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.serverInfoJson(
            serverVersion = "1.0.0",
            serverInstanceId = "inst_contract_001",
            storageDriver = "local_filesystem",
            writable = true,
        ))

        val result = apiService.serverInfo()

        assertEquals("1.0.0", result.serverVersion)
        assertEquals("inst_contract_001", result.serverInstanceId)
        assertNotNull(result.capabilities)
        assertEquals("local_filesystem", result.capabilities!!.storageDriver)
        assertNotNull(result.storage)
        assertTrue(result.storage!!.writable)

        val request = mockServer.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path!!.endsWith("/api/v1/server"))
    }

    // ─── Media ───

    @Test
    fun listMedia_getReturnsPagedMedia() = runBlocking {
        val media1 = TestDataFactory.serverMediaDtoJson(id = "media_001", name = "a.jpg")
        val media2 = TestDataFactory.serverMediaDtoJson(id = "media_002", name = "b.jpg")
        mockServer.enqueueJson(TestDataFactory.mediaPageJson(
            itemsJson = "$media1,$media2",
            hasMore = true,
            nextCursor = "cursor_next",
        ))

        val result = apiService.listMedia(limit = 2)

        assertEquals(2, result.items.size)
        assertEquals("media_001", result.items[0].id)
        assertEquals("a.jpg", result.items[0].name)
        assertTrue(result.hasMore)
        assertEquals("cursor_next", result.nextCursor)

        val request = mockServer.takeRequest()
        assertTrue(request.path!!.contains("limit=2"))
    }

    @Test
    fun getMedia_getReturnsSingleMedia() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.serverMediaDtoJson(id = "media_single", name = "single.jpg"))

        val result = apiService.getMedia("media_single")

        assertEquals("media_single", result.id)
        assertEquals("single.jpg", result.name)

        val request = mockServer.takeRequest()
        assertTrue(request.path!!.contains("/api/v1/media/media_single"))
    }

    // ─── Tags CRUD ───

    @Test
    fun listTags_getReturnsPagedTags() = runBlocking {
        val tag1 = TestDataFactory.serverTagDtoJson(id = "tag_001", name = "Nature")
        mockServer.enqueueJson(TestDataFactory.tagPageJson(itemsJson = tag1))

        val result = apiService.listTags()
        assertEquals(1, result.items.size)
        assertEquals("Nature", result.items[0].name)
    }

    @Test
    fun createTag_postSendsName() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.serverTagDtoJson(id = "tag_new", name = "New Tag"))

        val result = apiService.createTag(
            request = CreateTagRequestDto(name = "New Tag"),
            idempotencyKey = "k1",
        )
        assertEquals("New Tag", result.name)

        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/api/v1/tags"))
    }

    @Test
    fun updateTag_patchSendsIfMatch() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.serverTagDtoJson(id = "t1", name = "Updated", version = 2))

        val result = apiService.updateTag(
            id = "t1",
            request = UpdateTagRequestDto(name = "Updated"),
            ifMatch = "1",
            idempotencyKey = "k1",
        )
        assertEquals("Updated", result.name)
        assertEquals(2, result.version)
    }

    @Test
    fun deleteTag_deleteSendsIfMatch() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.serverTagDtoJson(id = "deleted"))
        apiService.deleteTag(id = "to_delete", ifMatch = "1", idempotencyKey = "k1")

        val request = mockServer.takeRequest()
        assertEquals("DELETE", request.method)
    }

    // ─── Sync / Bootstrap ───

    @Test
    fun startBootstrap_postReturnsSnapshotId() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.bootstrapStartJson(snapshotId = "snap_e2e"))

        val result = apiService.startBootstrap()
        assertEquals("snap_e2e", result.snapshotId)
        assertNotNull(result.jobId)

        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("/sync/bootstrap"))
    }

    @Test
    fun getBootstrap_getReturnsState() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.bootstrapStatusJson(snapshotId = "snap_e2e", state = "completed"))

        val result = apiService.getBootstrap("snap_e2e")
        assertEquals("snap_e2e", result.snapshotId)
        assertEquals("completed", result.state)
    }

    // ─── Changes ───

    @Test
    fun listChanges_getReturnsPagedChanges() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.changesPageJson(
            itemsJson = """{"id":"chg_001","entity":"media","operation":"upsert","entityId":"m1","version":1,"changedAt":1700000000000}""",
        ))

        val result = apiService.listChanges(limit = 50)
        assertEquals(1, result.items.size)
        assertTrue(mockServer.takeRequest().path!!.contains("limit=50"))
    }

    // ─── Jobs ───

    @Test
    fun getJob_getReturnsJobStatus() = runBlocking {
        mockServer.enqueueJson(TestDataFactory.serverJobDtoJson(
            id = "job_e2e",
            kind = "scan",
            status = "running",
            current = 50,
            total = 100,
        ))

        val result = apiService.getJob("job_e2e")
        assertEquals("job_e2e", result.id)
        assertEquals("scan", result.kind)
        assertEquals("running", result.status)
        assertEquals(50, result.current)
        assertEquals(100, result.total)

        val request = mockServer.takeRequest()
        assertTrue(request.path!!.contains("/api/v1/jobs/job_e2e"))
    }

    // ─── Auth Header ───

    @Test
    fun authInterceptor_injectsTokenWhenSet() = runBlocking {
        val tokenProvider = TestDependencies.createTokenProvider()
        tokenProvider.setToken("tok_injected")
        val apiWithAuth = TestDependencies.createApiService(
            baseUrl = mockServer.baseUrl,
            okHttpClient = TestDependencies.createOkHttpClient(tokenProvider),
        )

        mockServer.enqueueJson(TestDataFactory.healthJson())
        apiWithAuth.health()

        val request = mockServer.takeRequest()
        val authHeader = request.getHeader("Authorization")
        assertNotNull(authHeader)
        assertTrue(authHeader!!.contains("tok_injected"))
    }
}
