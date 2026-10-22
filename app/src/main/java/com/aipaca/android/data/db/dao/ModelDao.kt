package com.aipaca.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.aipaca.android.data.db.entity.ModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {

    @Insert
    suspend fun insert(model: ModelEntity): Long

    @Update
    suspend fun update(model: ModelEntity)

    @Delete
    suspend fun delete(model: ModelEntity)

    @Query("SELECT * FROM models ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE path = :path")
    suspend fun getByPath(path: String): ModelEntity?

    @Query("UPDATE models SET toolsSupported = :value WHERE path = :path")
    suspend fun updateToolsSupported(path: String, value: Int?)
}
