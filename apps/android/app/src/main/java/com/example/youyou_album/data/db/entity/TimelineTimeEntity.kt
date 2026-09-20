package com.example.youyou_album.data.db.entity
import androidx.room.Entity
/** Identity is scoped to the active server. It survives projection/local representative changes. */
@Entity(tableName="timeline_times", primaryKeys=["namespace", "hash"])
data class TimelineTimeEntity(val namespace: String, val hash: String, val at: Long?, val source: String, @androidx.room.ColumnInfo(defaultValue = "''") val sortKey: String = "")
