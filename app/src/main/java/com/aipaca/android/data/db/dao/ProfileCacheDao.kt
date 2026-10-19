package com.aipaca.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aipaca.android.data.db.entity.ProfileCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ProfileCacheEntity)

    @Delete
    suspend fun delete(entry: ProfileCacheEntity)

    @Query("SELECT * FROM profiles_cache WHERE key = :key")
    suspend fun get(key: String): ProfileCacheEntity?

    @Query("DELETE FROM profiles_cache")
    suspend fun deleteAll()

    @Query("SELECT * FROM profiles_cache ORDER BY fetchedAt DESC")
    fun observeAll(): Flow<List<ProfileCacheEntity>>
}
