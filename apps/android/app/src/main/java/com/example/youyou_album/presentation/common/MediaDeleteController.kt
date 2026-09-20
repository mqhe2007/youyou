package com.example.youyou_album.presentation.common

import com.example.youyou_album.service.MediaDeletionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 三个删除入口共用的编排器：把删除协调器的显式步骤转成可观察状态，
 * 由页面通过 [com.example.youyou_album.presentation.widgets.MediaDeleteLauncher]
 * 落到真实 Activity 回调。
 */
class MediaDeleteController(
    private val service: MediaDeletionService,
    private val scope: CoroutineScope,
) {
    private val _step = MutableStateFlow<MediaDeletionService.DeleteStep?>(null)
    val step: StateFlow<MediaDeletionService.DeleteStep?> = _step.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun delete(photoIds: List<String>) {
        if (photoIds.isEmpty() || _busy.value) return
        _busy.value = true
        scope.launch { handle(service.begin(photoIds)) }
    }

    fun onSystemConfirmation(session: MediaDeletionService.DeleteSession, approved: Boolean) {
        scope.launch { handle(service.onSystemConfirmation(session, approved)) }
    }

    fun clearMessage() {
        _message.value = null
    }

    private suspend fun handle(step: MediaDeletionService.DeleteStep) {
        when (step) {
            is MediaDeletionService.DeleteStep.Finished -> {
                _busy.value = false
                _step.value = null
                _message.value = step.summary.message()
            }
            else -> _step.value = step
        }
    }
}
