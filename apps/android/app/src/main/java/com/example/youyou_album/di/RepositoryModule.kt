package com.example.youyou_album.di

import com.example.youyou_album.data.repository.PhotoRepositoryImpl
import com.example.youyou_album.data.repository.TagRepositoryImpl
import com.example.youyou_album.data.repository.TaskRepositoryImpl
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TagRepository
import com.example.youyou_album.domain.repository.TaskRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPhotoRepository(impl: PhotoRepositoryImpl): PhotoRepository

    @Binds
    @Singleton
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository
}
