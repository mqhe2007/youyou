package com.example.youyou_album.presentation.widgets

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteConfirmMessageTest {

    @Test
    fun localOnlyOfflineMentionsRemoteCopiesRetained() {
        val message = deleteConfirmMessage(count = 3, online = false, hasLocal = true, hasRemote = false)
        assertTrue(message.contains("3 项"))
        assertTrue(message.contains("远程副本"))
    }

    @Test
    fun onlineRemoteMentionsServerRecycleBin() {
        val message = deleteConfirmMessage(count = 1, online = true, hasLocal = true, hasRemote = true)
        assertTrue(message.contains("这项"))
        assertTrue(message.contains("回收站"))
        assertTrue(message.contains("30 天"))
    }

    @Test
    fun offlineWithRemoteMentionsNeedServerConnection() {
        val message = deleteConfirmMessage(count = 1, online = false, hasLocal = true, hasRemote = true)
        assertTrue(message.contains("仅删除本机"))
        assertTrue(message.contains("连接服务端"))
        assertFalse(message.contains("30 天"))
    }

    @Test
    fun remoteOnlyLocalEffectOmitted() {
        val message = deleteConfirmMessage(count = 1, online = true, hasLocal = false, hasRemote = true)
        assertFalse(message.contains("本机原件"))
    }

    @Test
    fun localDeleteUsesSystemRecycleBinOnSupportedPlatforms() {
        val message = deleteConfirmMessage(count = 2, online = true, hasLocal = true, hasRemote = false)
        assertTrue(message.contains("系统回收站"))
        assertFalse(message.contains("永久删除"))
    }
}
