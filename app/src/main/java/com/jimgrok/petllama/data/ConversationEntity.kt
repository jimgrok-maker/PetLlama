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
    val role: String,          // "user" | "assistant" | "system" | "tool"
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Set on role "tool": "Server name / tool name" shown in the chat. */
    val toolName: String? = null,
    /** Raw JSON arguments for a tool bubble. */
    val toolArgs: String? = null
)

data class ConversationWithMessages(
    @Embedded val conversation: ConversationEntity,
    val messages: List<MessageEntity>
)
