package com.example.youyou_album.service

import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.api.dto.MediaOperationDto
import com.example.youyou_album.data.api.dto.ServerInfoDto
import com.example.youyou_album.data.api.interceptor.TokenProvider
import com.example.youyou_album.data.db.dao.MediaOperationDao
import com.example.youyou_album.data.db.entity.PendingMediaOperationEntity
import com.example.youyou_album.domain.model.ServerConnection
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RemoteDeletionIdentityTest {
    private val store = mockk<ServerConnectionStore>()
    private val tokens = TokenProvider().apply { setToken("test-token") }
    private val api = mockk<YouyouApiService>()
    private val factory = mockk<ApiServiceFactory>()
    private val hash = mockk<ContentHashService>()
    private val dao = mockk<MediaOperationDao>(relaxed = true)
    private val service = RemoteDeletionIdentity(store, tokens, factory, hash, dao)

    init {
        coEvery { store.getConnection() } returns ServerConnection(baseUrl = "http://test")
        every { factory.create("http://test", mediaOperations = true) } returns api
        every { hash.sha256HexForString(any()) } answers { firstArg() }
        coEvery { api.serverInfo("Bearer test-token") } returns ServerInfoDto(serverInstanceId = "instance", userId = "7")
    }

    private fun operation(scope: String) = PendingMediaOperationEntity(
        operationId = "operation", kind = "delete", serverNamespace = scope,
        serverMediaId = "media", localPhotoId = "photo", state = "unknown", createdAt = 1, updatedAt = 1,
    )

    @Test fun reconnectQueriesOnlyCurrentUserAndNeverDeletes() = runTest {
        val scope = service.capture()!!.scope
        coEvery { dao.listUnresolved() } returns listOf(operation(scope), operation("identity_other-user"))
        coEvery { api.getMediaOperation("operation", "Bearer test-token") } returns
            MediaOperationDto(mediaId = "media", kind = "delete", state = "succeeded")
        service.reconcile()
        coVerify(exactly = 1) { api.getMediaOperation(any(), any()) }
        coVerify { dao.updateOperationState("operation", "succeeded", null, any()) }
        coVerify(exactly = 0) { api.deleteMedia(any(), any(), any()) }
    }

    @Test fun missingResultStaysUnknownWithoutReplay() = runTest {
        val scope = service.capture()!!.scope
        coEvery { dao.listUnresolved() } returns listOf(operation(scope))
        coEvery { api.getMediaOperation(any(), any()) } throws java.io.IOException("result unavailable")
        service.reconcile()
        coVerify(exactly = 0) { dao.updateOperationState(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.deleteMedia(any(), any(), any()) }
    }

    @Test fun unbindAndReconnectWithSameTokenInvalidatesOldConfirmation() = runTest {
        val session = service.capture()!!
        tokens.setToken(null)
        tokens.setToken("test-token")
        assertFalse(service.isCurrent(session))
    }

    @Test fun oldServerWithoutUserIdentityCannotAuthorizeRemoteDelete() = runTest {
        coEvery { api.serverInfo(any()) } returns ServerInfoDto(serverInstanceId = "instance")
        assertNull(service.capture())
    }

    @Test fun mismatchedMediaResultCannotResolveOperation() = runTest {
        val scope = service.capture()!!.scope
        coEvery { dao.listUnresolved() } returns listOf(operation(scope))
        coEvery { api.getMediaOperation(any(), any()) } returns
            MediaOperationDto(mediaId = "other-media", kind = "delete", state = "succeeded")
        service.reconcile()
        coVerify(exactly = 0) { dao.updateOperationState(any(), any(), any(), any()) }
    }
}
