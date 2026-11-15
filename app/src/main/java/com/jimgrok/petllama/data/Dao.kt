package com.jimgrok.petllama.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY id DESC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: Long): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerEntity): Long

    @Delete
    suspend fun delete(server: ServerEntity)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(serverId: Long): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: Long): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt ASC")
    suspend fun messagesFor(cid: Long): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(m: MessageEntity): Long

    @Delete
    suspend fun deleteMessage(m: MessageEntity)
}
