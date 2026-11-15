package com.jimgrok.petllama.data

import android.content.Context
import androidx.room.Room

object Db {
    private lateinit var db: PetLlamaDatabase

    fun init(context: Context) {
        if (this::db.isInitialized) return
        db = Room.databaseBuilder(context, PetLlamaDatabase::class.java, "petllama.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val servers: ServerDao get() = db.serverDao()
    val conversations: ConversationDao get() = db.conversationDao()
}
