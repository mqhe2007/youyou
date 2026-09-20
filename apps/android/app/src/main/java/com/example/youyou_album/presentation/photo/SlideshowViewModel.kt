package com.example.youyou_album.presentation.photo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.repository.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SlideshowViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
) : ViewModel() {

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadPhotos()
    }

    fun loadPhotos() {
        viewModelScope.launch {
            _isLoading.value = true
            // 与时间线同源：服务端投影按当前身份作用域过滤，避免播放到不可见的陈旧投影。
            val all = photoRepository.getTimeline()
            _photos.value = all.sortedWith(compareByDescending<Photo> { it.sortAt }.thenBy { it.contentHash ?: it.id }.thenBy { it.id })
            _isLoading.value = false
        }
    }
}
