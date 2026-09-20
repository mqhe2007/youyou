import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningProperties = Properties()
val releaseSigningFile = rootProject.file("../../release-signing/keystore.properties")
val hasReleaseSigning = releaseSigningFile.exists()
if (hasReleaseSigning) {
    releaseSigningFile.inputStream().use(releaseSigningProperties::load)
}

android {
    namespace = "com.example.youyou_album"
    compileSdk = 37
    // NDK r27+ 默认生成 16KB 页面对齐的原生库
    ndkVersion = "30.0.15729638"

    defaultConfig {
        applicationId = "com.mengqinghe.youyou"
        minSdk = 31
        targetSdk = 37
        versionCode = 10
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.projectDir.parentFile.parentFile.resolve(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose BOM（2025.03+ 包含 16KB 对齐的 graphics-path 原生库）
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("com.composables:icons-lucide-android:2.2.1")
    // 运行期 Compose 会被 telephoto / dragselect / shimmer 的传递依赖拉到 1.10.0，
    // 而 BOM 只到 1.7.8：编译按旧签名、运行按新实现。共享元素这类在两个版本间
    // 改过类型名的 API 会在运行时直接 NoSuchMethodError，因此按运行期版本对齐。
    implementation("androidx.compose.animation:animation:1.10.0")
    implementation("androidx.compose.ui:ui:1.10.0")
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.runtime:runtime:1.10.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Google Photos 式长按拖选（LazyGrid）
    implementation("com.dragselectcompose:dragselect:3.3.0")

    // Zoomable image (双指缩放 + 与 ViewPager 手势协调)
    implementation("me.saket.telephoto:zoomable-image-coil:0.19.0")

    // Shimmer 骨架屏
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.1")

    // EXIF 读取
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Video
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")

    // CameraX + ML Kit（barcode-scanning 17.3+ 原生库已 16KB 对齐）
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // DataStore（1.1.2+ 原生库已 16KB 对齐）
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DocumentFile (for SAF access)
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("io.mockk:mockk:1.13.12")

    // Android instrumented tests (E2E on emulator)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.60.1")
    androidTestImplementation("androidx.navigation:navigation-testing:2.8.4")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.60.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        check(hasReleaseSigning) {
            "缺少 release-signing/keystore.properties，无法生成可安装的签名 Release APK。\n" +
              "请在仓库根目录建立该文件，包含 storeFile / storePassword / keyAlias / keyPassword 四项。"
        }
    }
}
