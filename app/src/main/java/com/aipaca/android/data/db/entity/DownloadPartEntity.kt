package com.aipaca.android.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "download_parts", indices = [Index("downloadId")])
data class DownloadPartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val downloadId: Long,
    val partIndex: Int,
    val start: Long,
    val end: Long,
    val done: Boolean,
    val downloadedBytes: Long = 0L,
)
