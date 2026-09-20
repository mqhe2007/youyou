package com.example.youyou_album.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS album_photos_table")
        db.execSQL("DROP TABLE IF EXISTS albums_table")
    }
}
