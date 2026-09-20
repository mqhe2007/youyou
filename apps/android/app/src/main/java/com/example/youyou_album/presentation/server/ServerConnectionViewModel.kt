package com.example.youyou_album.presentation.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.youyou_album.data.api.dto.PairRequestDto
import com.example.youyou_album.data.api.interceptor.TokenProvider
import com.example.youyou_album.data.db.dao.ServerProjectionDao
import com.example.youyou_album.data.db.dao.ServerSyncStateDao
import com.example.youyou_album.domain.model.ServerConnection
import com.example.youyou_album.presentation.common.ServerReachability
import com.example.youyou_album.service.ApiServiceFactory
import com.example.youyou_album.service.RemoteAccountCacheCleaner
import com.example.youyou_album.service.SecureStorageService
import com.example.youyou_album.service.ServerConnectionStore
import com.example.youyou_album.service.ServerSyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻提示反馈。成功与失败走同一位置、同一形态（药丸），只有填充色不同，
 * 因此页面只需观察一个字段，不必再在页尾塞一条红字。
 * [id] 保证连续两次相同文案也能各自触发一次提示。
 */
data class SnackbarFeedback(
    val message: String,
    val isError: Boolean,
    val id: Long,
)

/** 状态头里「远程媒体 / 上次同步」两个指标的数据源汇总。 */
data class ServerStats(
    val remoteMediaCount: Int = 0,
    val lastSyncedAt: Long? = null,
)

data class ServerConnectionUiState(
    val connection: ServerConnection? = null,
    val reachability: ServerReachability = ServerReachability.UNBOUND,
    /** 健康检查读到的最新版本；离线时为 null，回落到 [ServerConnection.serverVersion]。 */
    val liveServerVersion: String? = null,
    val isConnecting: Boolean = false,
    val connectingBaseUrl: String? = null,
    val connectionStage: ConnectionStage = ConnectionStage.IDLE,
    val feedback: SnackbarFeedback? = null,
)

/**
 * 连接阶段。`step` 直接驱动连接中态的四段进度（1..4），
 * 不再像旧版那样把四个阶段名写成一串静态文字。
 */
enum class ConnectionStage(val label: String, val short: String, val step: Int) {
    IDLE("", "", 0),
    PAIRING("正在配对", "配对", 1),
    VERIFYING("正在验证身份", "验证身份", 2),
    SYNCING("正在同步清单", "同步清单", 3),
    COMPLETE("连接完成", "完成", 4),
}

/** 四段进度的图例顺序，由 [ConnectionStage.step] 派生，避免两处维护。 */
val CONNECTION_STAGE_NAMES: List<String> =
    ConnectionStage.entries.filter { it.step > 0 }.map { it.short }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ServerConnectionViewModel @Inject constructor(
    private val serverConnectionStore: ServerConnectionStore,
    private val secureStorageService: SecureStorageService,
    private val tokenProvider: TokenProvider,
    private val serverSyncService: ServerSyncService,
    private val remoteAccountCacheCleaner: RemoteAccountCacheCleaner,
    private val apiServiceFactory: ApiServiceFactory,
    private val serverSyncStateDao: ServerSyncStateDao,
    private val serverProjectionDao: ServerProjectionDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerConnectionUiState())
    val uiState: StateFlow<ServerConnectionUiState> = _uiState.asStateFlow()

    private val feedbackIds = AtomicLong(0)

    /**
     * 远程媒体数与上次同步时间。namespace 取自 `server_sync_state`（当前唯一活跃身份），
     * 因此换绑、断开后会自动跟着变。
     */
    val serverStats: StateFlow<ServerStats> = serverSyncStateDao.observe()
        .map { state -> state?.serverNamespace?.takeIf { it.isNotBlank() } to state?.updatedAt }
        .distinctUntilChanged()
        .flatMapLatest { (namespace, updatedAt) ->
            if (namespace == null) {
                flowOf(ServerStats())
            } else {
                serverProjectionDao.observeCount(namespace)
                    .map { ServerStats(remoteMediaCount = it, lastSyncedAt = updatedAt) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerStats())

    init {
        observeConnection()
    }

    /** 连接信息变化即复核可达性：连接、断开、换绑都在同一条链上收口。 */
    private fun observeConnection() {
        viewModelScope.launch {
            serverConnectionStore.connectionFlow.collectLatest { connection ->
                _uiState.update {
                    it.copy(
                        connection = connection,
                        reachability = if (connection == null) {
                            ServerReachability.UNBOUND
                        } else {
                            ServerReachability.CHECKING
                        },
                        liveServerVersion = null,
                    )
                }
                if (connection == null) return@collectLatest
                // serverInfo 是鉴权接口，检查前先把设备令牌装上
                tokenProvider.setToken(secureStorageService.getDeviceToken())
                checkHealth(connection.baseUrl)
            }
        }
    }

    /**
     * 进入页面 / 连接变化时的一次健康检查。
     *
     * 用 `serverInfo`（而非 `health`）一次拿到可达性与服务端版本：可达性决定状态头怎么说话，
     * 版本进「服务端版本」指标位。
     */
    fun checkHealth(baseUrl: String? = null) {
        val url = baseUrl ?: _uiState.value.connection?.baseUrl ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(reachability = ServerReachability.CHECKING) }
            try {
                val info = apiServiceFactory.create(url).serverInfo()
                _uiState.update {
                    it.copy(
                        reachability = ServerReachability.ONLINE,
                        liveServerVersion = info.serverVersion.ifBlank { null },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(reachability = ServerReachability.OFFLINE, liveServerVersion = null)
                }
            }
        }
    }

    fun connect(baseUrl: String, code: String, deviceName: String) {
        viewModelScope.launch {
            val normalizedUrl = baseUrl.trim().trimEnd('/')
            _uiState.update {
                it.copy(
                    isConnecting = true,
                    connectingBaseUrl = normalizedUrl,
                    connectionStage = ConnectionStage.PAIRING,
                    feedback = null,
                )
            }
            try {
                val apiService = apiServiceFactory.create(normalizedUrl)
                val credentials = apiService.pair(PairRequestDto(code = code, deviceName = deviceName))
                secureStorageService.saveDeviceToken(credentials.token)
                secureStorageService.saveDeviceId(credentials.deviceId)
                tokenProvider.setToken(credentials.token)

                _uiState.update { it.copy(connectionStage = ConnectionStage.VERIFYING) }
                val serverInfo = apiService.serverInfo()
                if (serverInfo.storage?.writable != true) {
                    throw IllegalStateException("服务端媒体目录不可写，请联系管理员检查存储目录和权限。")
                }
                // 配对成功后才切换身份；切换前清空旧身份的远程投影与缓存。
                remoteAccountCacheCleaner.clear()
                val connection = ServerConnection(
                    baseUrl = normalizedUrl,
                    serverInstanceId = serverInfo.serverInstanceId,
                    deviceId = credentials.deviceId,
                    deviceName = deviceName,
                    serverVersion = serverInfo.serverVersion.ifBlank { null },
                )
                serverConnectionStore.saveConnection(connection)
                _uiState.update {
                    it.copy(
                        connection = connection,
                        connectionStage = ConnectionStage.SYNCING,
                        liveServerVersion = serverInfo.serverVersion.ifBlank { null },
                        reachability = ServerReachability.ONLINE,
                    )
                }
                serverSyncService.sync(normalizedUrl)
                _uiState.update {
                    it.copy(
                        connection = connection,
                        isConnecting = false,
                        connectingBaseUrl = null,
                        connectionStage = ConnectionStage.COMPLETE,
                        feedback = feedback("已连接服务端"),
                    )
                }
            } catch (e: retrofit2.HttpException) {
                finishConnectWithError(
                    when (e.code()) {
                        409 -> "二维码已过期或已使用"
                        429 -> "尝试次数过多，请稍后再试"
                        400, 401 -> "二维码无效，请重新生成"
                        426 -> "客户端版本过低，请先更新"
                        else -> "连接失败：HTTP ${e.code()}"
                    }
                )
            } catch (e: Exception) {
                finishConnectWithError(
                    when (e) {
                        is java.net.UnknownHostException,
                        is java.net.ConnectException,
                        is java.net.SocketTimeoutException -> "无法连接服务端，请检查地址与局域网"
                        else -> "连接失败：${e.message ?: "未知错误"}"
                    }
                )
            }
        }
    }

    private fun finishConnectWithError(message: String) {
        _uiState.update {
            it.copy(
                isConnecting = false,
                connectingBaseUrl = null,
                connectionStage = ConnectionStage.IDLE,
                feedback = feedback(message, isError = true),
            )
        }
    }

    /**
     * 扫码后自动解析二维码并配对。
     * 设备名称自动从 Build.MODEL 获取。
     */
    fun connectWithQr(qrRawValue: String) {
        try {
            val payload = ServerConnectionQrPayload.fromRawValue(qrRawValue)
            val deviceName = android.os.Build.MODEL.ifBlank { "youyou-android" }
            connect(payload.serverUrl, payload.pairingCode, deviceName)
        } catch (e: IllegalArgumentException) {
            _uiState.update {
                it.copy(
                    isConnecting = false,
                    connectingBaseUrl = null,
                    connectionStage = ConnectionStage.IDLE,
                    feedback = feedback(e.message ?: "二维码无效", isError = true),
                )
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            // 先通知服务端撤销设备（失败不影响本地断开）
            val currentBaseUrl = _uiState.value.connection?.baseUrl
            if (!currentBaseUrl.isNullOrBlank()) {
                try {
                    apiServiceFactory.create(currentBaseUrl).revokeSelfDevice()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 服务端不可达或已撤销，忽略错误，继续清除本地数据
                }
            }
            serverConnectionStore.clearConnection()
            secureStorageService.clearDeviceToken()
            secureStorageService.clearDeviceId()
            tokenProvider.setToken(null)
            remoteAccountCacheCleaner.clear()
            _uiState.update {
                it.copy(
                    connection = null,
                    reachability = ServerReachability.UNBOUND,
                    liveServerVersion = null,
                    feedback = feedback("已断开连接"),
                )
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    private fun feedback(message: String, isError: Boolean = false) = SnackbarFeedback(
        message = message,
        isError = isError,
        id = feedbackIds.incrementAndGet(),
    )
}
