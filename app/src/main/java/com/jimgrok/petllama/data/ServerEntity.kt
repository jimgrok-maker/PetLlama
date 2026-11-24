package com.jimgrok.petllama.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,          // e.g. http://192.168.1.50:8080 or http://100.x.y.z:8080
    val apiKey: String = "",
    /** Tokens in the context window. Null means detect it from the server. */
    val contextLength: Int? = null
)
