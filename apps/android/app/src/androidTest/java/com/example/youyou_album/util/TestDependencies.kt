package com.example.youyou_album.util

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.api.interceptor.AuthInterceptor
import com.example.youyou_album.data.api.interceptor.TokenProvider
import com.example.youyou_album.service.SecureStorageService
import com.example.youyou_album.service.ServerConnectionStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 测试依赖工厂：手动创建测试所需的各种依赖，不依赖 Hilt。
 *
 * 为什么不用 Hilt 测试？
 * Hilt 的 @HiltAndroidTest + @CustomTestApplication 在 androidTest 仪器化测试中
 * 存在组件生成兼容性问题（DaggerDefault_HiltComponents_SingletonC 无法生成）。
 * 手动 DI 更可靠，且业务功能测试本身就应该精确控制依赖。
 */
object TestDependencies {

    /** 获取测试用 Context（目标应用的 Context） */
    val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** JSON 序列化器（与生产代码一致） */
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Token 提供者（测试中可手动设置 token） */
    fun createTokenProvider(): TokenProvider = TokenProvider()

    /** OkHttpClient（测试用短超时） */
    fun createOkHttpClient(tokenProvider: TokenProvider = createTokenProvider()): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(logging)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** 创建指向指定 baseUrl 的 API 服务 */
    fun createApiService(
        baseUrl: String,
        okHttpClient: OkHttpClient = createOkHttpClient(),
    ): YouyouApiService {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl("$baseUrl${YouyouApiService.API_PREFIX}")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(YouyouApiService::class.java)
    }

    /** 创建内存数据库（每个测试实例独立） */
    fun createInMemoryDatabase(): AppDatabase {
        return Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    /** 创建安全存储服务 */
    fun createSecureStorage(): SecureStorageService = SecureStorageService(context)

    /** 创建服务器连接存储 */
    fun createServerConnectionStore(): ServerConnectionStore = ServerConnectionStore(context)

    /** 清理安全存储中的测试数据 */
    fun clearSecureStorage() {
        val storage = createSecureStorage()
        storage.clearDeviceToken()
        // SecureStorageService 没有公开清除 deviceId 的方法，通过反射或直接清除 SharedPreferences
        try {
            val prefsField = SecureStorageService::class.java.getDeclaredField("prefs")
            prefsField.isAccessible = true
            val prefs = prefsField.get(storage) as android.content.SharedPreferences
            prefs.edit().clear().apply()
        } catch (_: Exception) {
            // 忽略
        }
    }

    /** 清理服务器连接存储 */
    suspend fun clearServerConnectionStore() {
        createServerConnectionStore().clearConnection()
    }

    // ─── 真机保护 ────────────────────────────────────────────────────────────

    /** 显式放行「在真机上改写应用真实状态」的仪器化参数名（默认关闭）。 */
    const val ARG_ALLOW_REAL_DEVICE_STATE = "youyou.allow-real-device-state"

    /**
     * 是否跑在**物理设备（真机）**上。
     *
     * 模拟器：`ro.hardware` 为 `ranchu` / `goldfish`，指纹含 `sdk_gphone`（Pixel_10_Pro AVD 即如此）；
     * 真机：如基准机 NOH-AN01 的 `kirin9000` / `HUAWEI/NOH-AN01/HWNOH`。
     */
    val isRealDevice: Boolean
        get() {
            val fingerprint = Build.FINGERPRINT.lowercase()
            val hardware = Build.HARDWARE.lowercase()
            val emulator = fingerprint.startsWith("generic") ||
                fingerprint.contains("sdk_gphone") ||
                fingerprint.contains("emulator") ||
                hardware == "ranchu" ||
                hardware == "goldfish"
            return !emulator
        }

    private val allowRealDeviceState: Boolean
        get() = InstrumentationRegistry.getArguments()
            .getString(ARG_ALLOW_REAL_DEVICE_STATE)
            .toBoolean()

    /**
     * 真机保护闸门：在真机上跳过会**改写应用真实状态**的用例。
     *
     * 本工程不使用 Hilt 测试运行器（见文件头注释），因此：
     * - UI 用例启动的是**真实 `MainActivity`**，走真实依赖图，会写真实 `youyou_album.db`；
     * - [clearSecureStorage] 清的是**真实 `SharedPreferences`**，[createServerConnectionStore] 清的是真实连接状态。
     *
     * 在模拟器上这些都没有副作用；但在**用户真机**上跑会清掉已配对身份、并让真实库被测试写入。
     * 所以默认在真机上跳过，确需在真机跑要显式放行：
     *
     * ```
     * ./gradlew :app:connectedDebugAndroidTest \
     *   -Pandroid.testInstrumentationRunnerArguments.youyou.allow-real-device-state=true
     * ```
     *
     * **真机上只应跑 `MediaTimeDatabaseTest`**——它用的是独立临时库（`time-*.db`），
     * 既不碰真实库、真实 prefs，也不碰真实媒体文件。
     */
    fun assumeNoRealUserState() {
        Assume.assumeFalse(
            "跳过：该用例会改写应用真实状态（真实 prefs / 真实库）。" +
                "真机上只应跑 MediaTimeDatabaseTest；确需在真机跑请加 $ARG_ALLOW_REAL_DEVICE_STATE=true",
            isRealDevice && !allowRealDeviceState,
        )
    }

    /**
     * 把 [assumeNoRealUserState] 包成规则，好在 **Activity 启动之前**判定。
     *
     * 必须用 `RuleChain.outerRule(guard).around(composeRule)` 组合：写在 `@Before` 里已经太晚——
     * Compose 规则在 `@Before` 之前就把 Activity 拉起来了，真实库那时已经被写过。
     */
    fun realDeviceGuard(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeNoRealUserState()
                base.evaluate()
            }
        }
    }
}
