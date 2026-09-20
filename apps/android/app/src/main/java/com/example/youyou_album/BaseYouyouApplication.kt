package com.example.youyou_album

import android.app.Application
import androidx.work.Configuration
import com.example.youyou_album.service.NotificationChannels

/**
 * 应用基类，不含 Hilt 注解。
 *
 * 之所以拆出基类，是因为 Hilt 的 @CustomTestApplication 要求 value 参数
 * 不能是 @HiltAndroidApp 注解的类。主应用 YouyouApplication 继承本类并添加
 * @HiltAndroidApp；测试侧通过 @CustomTestApplication(BaseYouyouApplication::class)
 * 生成独立的测试 Application，拥有独立的 Hilt 组件树。
 */
open class BaseYouyouApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
