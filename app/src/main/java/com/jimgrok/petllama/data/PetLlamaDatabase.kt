package com.jimgrok.petllama.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ServerEntity::class, ConversationEntity::class, MessageEntity::class, McpServerEntity::class],
    version = 4,
    exportSchema = false
)
abstract class PetLlamaDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun mcpServerDao(): McpServerDao
}
