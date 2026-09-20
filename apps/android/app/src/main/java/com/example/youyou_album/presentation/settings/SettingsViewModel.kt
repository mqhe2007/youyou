package com.example.youyou_album.presentation.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.example.youyou_album.data.db.dao.PhotoDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val cacheSizeBytes: Long = 0,
    val isCacheLoading: Boolean = true,
    val isClearingCache: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDao: PhotoDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCacheLoading = true)
            val size = calculateCacheSize()
            _uiState.value = _uiState.value.copy(cacheSizeBytes = size, isCacheLoading = false)
        }
    }

    fun clearCache(onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isClearingCache = true)
            try {
                deleteDirContents(context.cacheDir)
                // 也清除外部缓存
                context.externalCacheDir?.let { deleteDirContents(it) }
                // 置空缩略图路径：重建后数据库发生真实变化，时间线才会重组显示新缩略图
                photoDao.clearLocalThumbnailPaths()
                // Coil 磁盘缓存放在最后用其 API 清理：直接删目录会让同进程 journal 失效
                context.imageLoader.diskCache?.clear()
                _uiState.value = _uiState.value.copy(cacheSizeBytes = 0, isClearingCache = false)
                onResult(true, null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isClearingCache = false)
                onResult(false, e.message)
            }
        }
    }

    private fun calculateCacheSize(): Long {
        var total = 0L
        total += dirSize(context.cacheDir)
        context.externalCacheDir?.let { total += dirSize(it) }
        return total
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
