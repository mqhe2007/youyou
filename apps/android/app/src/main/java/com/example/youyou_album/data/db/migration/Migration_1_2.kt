package com.example.youyou_album.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE photos_table ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
    }
}
