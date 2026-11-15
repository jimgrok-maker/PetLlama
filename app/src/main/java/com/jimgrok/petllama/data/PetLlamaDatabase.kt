package com.jimgrok.petllama.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ServerEntity::class, ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PetLlamaDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun conversationDao(): ConversationDao
}
