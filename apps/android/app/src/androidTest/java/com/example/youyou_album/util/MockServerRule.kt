package com.example.youyou_album.util

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.rules.ExternalResource

/**
 * MockWebServer 测试规则：自动启动/关闭 MockWebServer。
 * 提供便捷的 enqueue 方法和请求断言。
 */
class MockServerRule : ExternalResource() {

    val server = MockWebServer()
    val baseUrl: String get() = server.url("/").toString()

    override fun before() {
        server.start()
    }

    override fun after() {
        server.shutdown()
    }

    /** 入队一个 JSON 响应 */
    fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .addHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    /** 入队一个空响应（如 204 No Content） */
    fun enqueueEmpty(code: Int = 204) {
        server.enqueue(MockResponse().setResponseCode(code))
    }

    /** 入队一个错误响应 */
    fun enqueueError(code: Int, message: String = "") {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"error":"$message"}""")
        )
    }

    /** 取走并返回下一个已记录请求 */
    fun takeRequest(): RecordedRequest = server.takeRequest()

    /** 获取已记录请求数量 */
    fun requestCount(): Int = server.requestCount
}
