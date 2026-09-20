package com.example.youyou_album.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // Phase 1: 提供数据库、API 客户端、仓库等依赖
}
