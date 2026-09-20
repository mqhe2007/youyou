package com.example.youyou_album.presentation.widgets

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.example.youyou_album.service.MediaDeletionService

/** 确认框打开时核对网络，不能把仍有绑定当作在线。 */
fun deleteNetworkAvailable(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
}

/**
 * 按在线状态生成应用内删除确认文案（需求 UoN5J--JHK_R §4）。
 * 系统框只表达系统授权，表达不了服务端后果，因此涉及远程删除时必须在此说明。
 */
fun deleteConfirmMessage(
    count: Int,
    online: Boolean,
    hasLocal: Boolean,
    hasRemote: Boolean,
): String {
    val scope = if (count == 1) "这项" else "这 $count 项"
    val localEffect = if (!hasLocal) "" else
        "本机原件将移入系统回收站，保留期限与恢复方式以系统相册为准。"
    val remoteEffectOnline = "服务端原件将移入回收站保留 30 天，可由管理员恢复。"
    val offlineEffect = "当前离线：仅删除本机原件，远程副本（如有）保留；仅远程的项目需连接服务端后才能删除。"
    val remoteNote = when {
        !hasRemote -> "远程副本（如有）保留。"
        online -> remoteEffectOnline
        else -> offlineEffect
    }
    return buildString {
        append("将删除${scope}原件。")
        if (localEffect.isNotEmpty()) append(localEffect)
        append(remoteNote)
        append("批量操作逐项执行，取消不会撤回已完成的删除；结果以实际反馈为准。")
    }
}

/**
 * 删除流程与 Activity 结果的桥接（需求 UoN5J--JHK_R §3）。
 *
 * `createTrashRequest` 必须由 Activity 发起（不能用后台服务或同步假实现替代），
 * 因此把 ViewModel 产出的 `DeleteStep` 在这里落到真实系统回调，并把结果原样送回 ViewModel 续跑。
 */
@Composable
fun MediaDeleteLauncher(
    step: MediaDeletionService.DeleteStep?,
    onSystemConfirmation: (MediaDeletionService.DeleteSession, Boolean) -> Unit,
) {
    val systemLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = step as? MediaDeletionService.DeleteStep.NeedsSystemConfirmation
            ?: return@rememberLauncherForActivityResult
        onSystemConfirmation(pending.session, result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(step) {
        when (step) {
            is MediaDeletionService.DeleteStep.NeedsSystemConfirmation ->
                systemLauncher.launch(IntentSenderRequest.Builder(step.intentSender).build())
            else -> Unit
        }
    }
}
