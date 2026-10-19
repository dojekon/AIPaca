package com.aipaca.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.aipaca.android.data.db.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Insert
    suspend fun insert(download: DownloadEntity): Long

    @Update
    suspend fun update(download: DownloadEntity)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("UPDATE downloads SET totalBytes = :totalBytes WHERE id = :id")
    suspend fun updateTotalBytes(id: Long, totalBytes: Long)

    @Query("UPDATE downloads SET downloadedBytes = :downloadedBytes WHERE id = :id AND state = 'running'")
    suspend fun updateDownloadedBytesWhileRunning(id: Long, downloadedBytes: Long)

    @Query("UPDATE downloads SET state = :state, completedAt = :completedAt, error = :error WHERE id = :id")
    suspend fun updateState(id: Long, state: String, completedAt: Long?, error: String?)

    @Query(
        "UPDATE downloads SET state = :state, completedAt = :completedAt, error = :error " +
            "WHERE id = :id AND state IN ('queued', 'running')",
    )
    suspend fun updateStateIfPending(id: Long, state: String, completedAt: Long?, error: String?)

    @Query(
        "UPDATE downloads SET state = :state, completedAt = :completedAt, error = :error " +
            "WHERE id = :id AND state = 'running'",
    )
    suspend fun updateStateWhileRunning(id: Long, state: String, completedAt: Long?, error: String?)
}
