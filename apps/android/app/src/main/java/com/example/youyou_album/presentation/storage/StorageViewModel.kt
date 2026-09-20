package com.example.youyou_album.presentation.storage

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class StorageInfo(
    val cacheSize: Long = 0,
    val thumbnailCacheSize: Long = 0,
    val originalCacheSize: Long = 0,
    val databaseSize: Long = 0,
    val totalAppSize: Long = 0,
    val totalDeviceStorage: Long = 0,
    val availableDeviceStorage: Long = 0,
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val photoDao: com.example.youyou_album.data.db.dao.PhotoDao,
) : ViewModel() {

    private val _storageInfo = MutableStateFlow(StorageInfo())
    val storageInfo: StateFlow<StorageInfo> = _storageInfo.asStateFlow()

    private val _isCleaning = MutableStateFlow(false)
    val isCleaning: StateFlow<Boolean> = _isCleaning.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { calculateStorageInfo() }
            _storageInfo.value = info
        }
    }

    fun clearThumbnailCache() {
        viewModelScope.launch {
            _isCleaning.value = true
            withContext(Dispatchers.IO) {
                deleteDirContents(File(context.cacheDir, "thumbnails"))
                photoDao.clearLocalThumbnailPaths()
            }
            refresh()
            _isCleaning.value = false
        }
    }

    fun clearOriginalCache() {
        viewModelScope.launch {
            _isCleaning.value = true
            withContext(Dispatchers.IO) {
                deleteDirContents(File(context.cacheDir, "originals"))
            }
            refresh()
            _isCleaning.value = false
        }
    }

    fun clearAllCache() {
        viewModelScope.launch {
            _isCleaning.value = true
            withContext(Dispatchers.IO) {
                deleteDirContents(context.cacheDir)
                photoDao.clearLocalThumbnailPaths()
            }
            refresh()
            _isCleaning.value = false
        }
    }

    private fun calculateStorageInfo(): StorageInfo {
        val cacheDir = context.cacheDir
        val thumbnailCache = File(cacheDir, "thumbnails")
        val originalCache = File(cacheDir, "originals")
        val databaseFile = context.getDatabasePath("youyou_album.db")

        val stat = android.os.StatFs(context.filesDir.absolutePath)
        val totalStorage = stat.totalBytes
        val availableStorage = stat.availableBytes

        return StorageInfo(
            cacheSize = dirSize(cacheDir),
            thumbnailCacheSize = dirSize(thumbnailCache),
            originalCacheSize = dirSize(originalCache),
            databaseSize = if (databaseFile.exists()) databaseFile.length() else 0,
            totalAppSize = dirSize(context.filesDir) + dirSize(cacheDir) +
                    (if (databaseFile.exists()) databaseFile.length() else 0),
            totalDeviceStorage = totalStorage,
            availableDeviceStorage = availableStorage,
        )
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) size += file.length()
        }
        return size
    }

    private fun deleteDirContents(dir: File) {
        if (!dir.exists()) return
        // 只删文件、保留目录：子目录本身被删会让构造期缓存路径的单例服务写入永久失败
        dir.walkTopDown().filter { it.isFile }.forEach { it.delete() }
    }
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
