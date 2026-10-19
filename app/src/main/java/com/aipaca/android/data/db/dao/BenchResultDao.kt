package com.aipaca.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.aipaca.android.data.db.entity.BenchResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BenchResultDao {

    @Insert
    suspend fun insert(result: BenchResultEntity): Long

    @Update
    suspend fun update(result: BenchResultEntity)

    @Delete
    suspend fun delete(result: BenchResultEntity)

    @Query("SELECT * FROM bench_results WHERE modelId = :modelId ORDER BY createdAt DESC")
    fun observeForModel(modelId: Long): Flow<List<BenchResultEntity>>

    @Query("SELECT * FROM bench_results ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BenchResultEntity>>
}
