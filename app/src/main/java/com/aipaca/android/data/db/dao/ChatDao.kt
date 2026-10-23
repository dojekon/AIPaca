package com.aipaca.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.aipaca.android.data.db.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Insert
    suspend fun insert(chat: ChatEntity): Long

    @Update
    suspend fun update(chat: ChatEntity)

    @Delete
    suspend fun delete(chat: ChatEntity)

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeById(id: Long): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE mode = :mode ORDER BY updatedAt DESC LIMIT :limit")
    fun observeForMode(mode: String, limit: Int): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getById(id: Long): ChatEntity?

    @Query("UPDATE chats SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTimestamp(id: Long, updatedAt: Long)

    @Query("UPDATE chats SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE chats SET title = :title WHERE id = :id AND title = :expectedTitle")
    suspend fun updateTitleIfMatches(id: Long, title: String, expectedTitle: String)

    @Query("UPDATE chats SET paramsJson = :paramsJson, systemPrompt = :systemPrompt WHERE id = :id")
    suspend fun updateSettings(id: Long, paramsJson: String?, systemPrompt: String?)
}
