package com.example.youyou_album.di

import com.example.youyou_album.data.api.interceptor.TokenProvider
import com.example.youyou_album.data.db.dao.ServerProjectionDao
import com.example.youyou_album.data.db.dao.ServerSyncStateDao
import com.example.youyou_album.data.db.dao.MediaOperationDao
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TaskRepository
import com.example.youyou_album.service.ApiServiceFactory
import com.example.youyou_album.service.MediaDeletionService
import com.example.youyou_album.service.RemoteAccountCacheCleaner
import com.example.youyou_album.service.SecureStorageService
import com.example.youyou_album.service.ServerConnectionStore
import com.example.youyou_album.service.ServerSyncService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Allows the emulator acceptance test to exercise the production dependency graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AcceptanceEntryPoint {
    fun apiServiceFactory(): ApiServiceFactory
    fun connectionStore(): ServerConnectionStore
    fun secureStorage(): SecureStorageService
    fun tokenProvider(): TokenProvider
    fun serverSyncService(): ServerSyncService
    fun photoRepository(): PhotoRepository
    fun taskRepository(): TaskRepository
    fun remoteAccountCacheCleaner(): RemoteAccountCacheCleaner
    fun serverSyncStateDao(): ServerSyncStateDao
    fun serverProjectionDao(): ServerProjectionDao
    fun mediaDeletionService(): MediaDeletionService
    fun mediaOperationDao(): MediaOperationDao
}
