package com.aipaca.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val systemPrompt: String? = null,
    val paramsJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val mode: String = ChatMode.CHAT,
)

object ChatMode {
    const val CHAT = "chat"
    const val AGENT = "agent"
}
