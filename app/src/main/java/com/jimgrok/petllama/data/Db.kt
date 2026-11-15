package com.jimgrok.petllama.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Db {
    private lateinit var db: PetLlamaDatabase

    /**
     * Keeps llama servers and chats that already exist on the phone.
     * Adds the MCP server table and the tool columns on messages.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `mcp_servers` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `url` TEXT NOT NULL,
                    `bearerToken` TEXT NOT NULL,
                    `headersJson` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `toolName` TEXT")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `toolArgs` TEXT")
        }
    }

    fun init(context: Context) {
        if (this::db.isInitialized) return
        db = Room.databaseBuilder(context, PetLlamaDatabase::class.java, "petllama.db")
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }

    val servers: ServerDao get() = db.serverDao()
    val conversations: ConversationDao get() = db.conversationDao()
    val mcp: McpServerDao get() = db.mcpServerDao()
}
