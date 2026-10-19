package com.aipaca.android.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val chatId: Long,
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val createdAt: Long,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val tps: Double? = null,
    val modelId: Long? = null,
)
