package com.aipaca.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles_cache")
data class ProfileCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val etag: String? = null,
    val fetchedAt: Long,
)
