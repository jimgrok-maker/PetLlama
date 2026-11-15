package com.jimgrok.petllama.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.Embedded

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val model: String,
    val title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,          // "user" | "assistant" | "system"
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class ConversationWithMessages(
    @Embedded val conversation: ConversationEntity,
    val messages: List<MessageEntity>
)
