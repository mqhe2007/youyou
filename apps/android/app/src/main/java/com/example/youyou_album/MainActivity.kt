package com.example.youyou_album

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.youyou_album.data.api.interceptor.TokenProvider
import com.example.youyou_album.presentation.navigation.AppNavGraph
import com.example.youyou_album.service.SecureStorageService
import com.example.youyou_album.ui.theme.YouyouTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenProvider: TokenProvider
    @Inject lateinit var secureStorageService: SecureStorageService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用启动时从 SecureStorage 恢复 token 到内存缓存（TokenProvider）
        // 否则直接进首页上传时 AuthInterceptor 拿不到 token，导致 401
        val hasToken = secureStorageService.getDeviceToken()?.isNotBlank() == true
        tokenProvider.setToken(secureStorageService.getDeviceToken())

        enableEdgeToEdge()
        setContent {
            YouyouTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    // 首启（未绑定服务端）先进扫码登录页；已绑定直接进主框架
                    AppNavGraph(navController = navController, startOnLogin = !hasToken)
                }
            }
        }
    }

}
