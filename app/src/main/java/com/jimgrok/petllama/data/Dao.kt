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
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY id DESC")
    fun observeAll(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers WHERE enabled = 1 ORDER BY id ASC")
    suspend fun listEnabled(): List<McpServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: McpServerEntity): Long

    @Update
    suspend fun update(server: McpServerEntity)

    @Delete
    suspend fun delete(server: McpServerEntity)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE serverId = :serverId ORDER BY updatedAt DESC")
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

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesFor(conversationId: Long)

    /** Removes the conversation and the messages that belong to it. */
    @Transaction
    suspend fun deleteConversation(conversation: ConversationEntity) {
        deleteMessagesFor(conversation.id)
        delete(conversation)
    }

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt ASC")
    suspend fun messagesFor(cid: Long): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(m: MessageEntity): Long

    @Delete
    suspend fun deleteMessage(m: MessageEntity)
}
