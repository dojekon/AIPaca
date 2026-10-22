package com.aipaca.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val repoId: String? = null,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val quant: String? = null,
    val arch: String? = null,
    val paramsB: Double? = null,
    val addedAt: Long,
    val lastUsedAt: Long? = null,
    val toolsSupported: Int? = null,
)
