package com.example.youyou_album.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.youyou_album.data.db.entity.ScanCursorEntity

@Dao
interface ScanCursorDao {
    @Query("SELECT * FROM scan_cursors_table WHERE scope = :scope")
    suspend fun getByScope(scope: String): ScanCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: ScanCursorEntity)

    @Query("DELETE FROM scan_cursors_table WHERE scope = :scope")
    suspend fun deleteByScope(scope: String)
}
