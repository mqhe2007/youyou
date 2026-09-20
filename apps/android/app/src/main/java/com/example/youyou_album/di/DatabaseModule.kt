package com.example.youyou_album.di

import android.content.Context
import androidx.room.Room
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.db.dao.MediaOperationDao
import com.example.youyou_album.data.db.dao.PhotoDao
import com.example.youyou_album.data.db.dao.ScanCursorDao
import com.example.youyou_album.data.db.dao.ServerExportDao
import com.example.youyou_album.data.db.dao.ServerProjectionDao
import com.example.youyou_album.data.db.dao.ServerSyncStateDao
import com.example.youyou_album.data.db.dao.TagDao
import com.example.youyou_album.data.db.dao.TaskDao
import com.example.youyou_album.data.db.migration.MIGRATION_1_2
import com.example.youyou_album.data.db.migration.MIGRATION_2_3
import com.example.youyou_album.data.db.migration.MIGRATION_5_6
import com.example.youyou_album.data.db.migration.MIGRATION_6_7
import com.example.youyou_album.data.db.migration.MIGRATION_4_5
import com.example.youyou_album.data.db.migration.MIGRATION_3_4
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
    }

    @Provides
    fun providePhotoDao(db: AppDatabase): PhotoDao = db.photoDao()

    @Provides
    fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()

    @Provides
    fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideScanCursorDao(db: AppDatabase): ScanCursorDao = db.scanCursorDao()

    @Provides
    fun provideServerProjectionDao(db: AppDatabase): ServerProjectionDao = db.serverProjectionDao()

    @Provides
    fun provideServerExportDao(db: AppDatabase): ServerExportDao = db.serverExportDao()

    @Provides
    fun provideServerSyncStateDao(db: AppDatabase): ServerSyncStateDao = db.serverSyncStateDao()

    @Provides
    fun provideMediaOperationDao(db: AppDatabase): MediaOperationDao = db.mediaOperationDao()
}
