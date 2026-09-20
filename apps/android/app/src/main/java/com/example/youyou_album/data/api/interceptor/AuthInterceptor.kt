package com.example.youyou_album.data.api.interceptor

import com.example.youyou_album.data.api.YouyouApiService
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 注入认证 token 和客户端版本头。
 * token 由 TokenProvider 在运行时提供（支持动态刷新）。
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenProvider: TokenProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val requestBuilder = original.newBuilder()
            .header("Accept", "application/json")
            .header("X-Youyou-Client-Version", YouyouApiService.CLIENT_VERSION)

        val token = tokenProvider.getToken()
        if (original.header("Authorization") == null && !token.isNullOrEmpty()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }
}

/**
 * 运行时 token 提供者，由 SecureStorage 或内存缓存实现。
 */
@Singleton
class TokenProvider @Inject constructor() {
    @Volatile
    private var token: String? = null

    @Volatile
    var generation: Long = 0
        private set

    fun getToken(): String? = token

    @Synchronized
    fun setToken(newToken: String?) {
        generation++
        token = newToken
    }
}
