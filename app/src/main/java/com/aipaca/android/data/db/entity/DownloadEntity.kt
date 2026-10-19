package com.aipaca.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val modelKey: String,
    val url: String,
    val targetPath: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val state: String,
    val connections: Int,
    val createdAt: Long,
    val completedAt: Long? = null,
    val error: String? = null,
)
