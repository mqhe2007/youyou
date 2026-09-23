package com.example.youyou_album.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.youyou_album.domain.model.ServerConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "youyou_prefs")

@Singleton
class ServerConnectionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyBaseUrl = stringPreferencesKey("server_base_url")
    private val keyServerInstanceId = stringPreferencesKey("server_instance_id")
    private val keyDeviceId = stringPreferencesKey("device_id")
    private val keyDeviceName = stringPreferencesKey("device_name")
    private val keyServerVersion = stringPreferencesKey("server_version")
    private val keyFirstConnectGuide = booleanPreferencesKey("first_connect_guide")

    val firstConnectGuidePending: Flow<Boolean> = context.dataStore.data.map { it[keyFirstConnectGuide] == true }

    suspend fun finishFirstConnectGuide() {
        context.dataStore.edit { it[keyFirstConnectGuide] = false }
    }

    val connectionFlow: Flow<ServerConnection?> = context.dataStore.data.map { prefs ->
        val baseUrl = prefs[keyBaseUrl] ?: return@map null
        ServerConnection(
            baseUrl = baseUrl,
            serverInstanceId = prefs[keyServerInstanceId],
            deviceId = prefs[keyDeviceId],
            deviceName = prefs[keyDeviceName],
            serverVersion = prefs[keyServerVersion],
        )
    }

    suspend fun getConnection(): ServerConnection? = connectionFlow.first()

    suspend fun saveConnection(connection: ServerConnection) {
        context.dataStore.edit { prefs ->
            prefs[keyBaseUrl] = connection.baseUrl
            connection.serverInstanceId?.let { prefs[keyServerInstanceId] = it }
            connection.deviceId?.let { prefs[keyDeviceId] = it }
            connection.deviceName?.let { prefs[keyDeviceName] = it }
            connection.serverVersion?.let { prefs[keyServerVersion] = it }
            prefs[keyFirstConnectGuide] = true
        }
    }

    suspend fun clearConnection() {
        context.dataStore.edit { prefs ->
            prefs.remove(keyBaseUrl)
            prefs.remove(keyServerInstanceId)
            prefs.remove(keyDeviceId)
            prefs.remove(keyDeviceName)
            prefs.remove(keyServerVersion)
            prefs.remove(keyFirstConnectGuide)
        }
    }
}
