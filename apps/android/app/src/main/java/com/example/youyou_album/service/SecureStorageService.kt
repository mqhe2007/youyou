package com.example.youyou_album.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorageService @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            context,
            "youyou_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        .also { context.deleteSharedPreferences("youyou_secure_prefs_fallback") }
    } catch (e: Exception) {
        context.deleteSharedPreferences("youyou_secure_prefs_fallback")
        Log.e(TAG, "secure storage initialization failed", e)
        null
    }

    val isAvailable: Boolean get() = prefs != null
    private fun requirePrefs(): SharedPreferences = prefs
        ?: throw IllegalStateException("安全存储不可用，请重新初始化设备安全存储后再配对")

    fun getDeviceToken(): String? = prefs?.getString(KEY_DEVICE_TOKEN, null)

    fun saveDeviceToken(token: String) {
        requirePrefs().edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    fun clearDeviceToken() {
        requirePrefs().edit().remove(KEY_DEVICE_TOKEN).apply()
    }

    fun getDeviceId(): String? = prefs?.getString(KEY_DEVICE_ID, null)

    fun saveDeviceId(deviceId: String) {
        requirePrefs().edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    fun clearDeviceId() {
        requirePrefs().edit().remove(KEY_DEVICE_ID).apply()
    }

    companion object {
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val TAG = "SecureStorage"
    }
}
