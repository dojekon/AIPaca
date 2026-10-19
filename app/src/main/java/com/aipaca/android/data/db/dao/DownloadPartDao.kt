package com.aipaca.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.aipaca.android.data.db.entity.DownloadPartEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadPartDao {

    @Insert
    suspend fun insert(part: DownloadPartEntity): Long

    @Update
    suspend fun update(part: DownloadPartEntity)

    @Delete
    suspend fun delete(part: DownloadPartEntity)

    @Query("SELECT * FROM download_parts WHERE downloadId = :downloadId ORDER BY partIndex ASC")
    fun observeForDownload(downloadId: Long): Flow<List<DownloadPartEntity>>

    @Query("SELECT * FROM download_parts WHERE downloadId = :downloadId ORDER BY partIndex ASC")
    suspend fun getForDownload(downloadId: Long): List<DownloadPartEntity>

    @Query("DELETE FROM download_parts WHERE downloadId = :downloadId")
    suspend fun deleteForDownload(downloadId: Long)
}
