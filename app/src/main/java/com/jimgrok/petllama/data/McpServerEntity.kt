package com.jimgrok.petllama.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An MCP server the model can call over Streamable HTTP.
 * [headersJson] is a JSON array of {"name","value"} objects. The bearer token,
 * when set, is sent as Authorization and wins over an Authorization header in that list.
 */
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val bearerToken: String = "",
    val headersJson: String = "[]",
    val enabled: Boolean = true
)
