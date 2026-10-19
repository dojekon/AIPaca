package com.aipaca.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bench_results")
data class BenchResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val modelId: Long,
    val deviceKey: String,
    val backend: String,
    val configJson: String,
    val pp: Double,
    val tg: Double,
    val createdAt: Long,
)
