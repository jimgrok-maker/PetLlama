package com.jimgrok.petllama.ui.mcp

import android.content.Context
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.data.McpServerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * What to do the first time the app looks at the MCP table.
 * [markSeeded] is true after that look, so a server the user later deletes
 * is not inserted again. A table that already has rows is left untouched.
 */
data class McpSeedPlan(
    val insert: List<McpServerEntity>,
    val markSeeded: Boolean
)

object DefaultMcpServers {
    const val PREFS_NAME = "petllama_mcp_seed"
    const val KEY_SEEDED = "seeded"

    fun plan(existingCount: Int, alreadySeeded: Boolean): McpSeedPlan {
        if (alreadySeeded) return McpSeedPlan(emptyList(), markSeeded = false)
        if (existingCount > 0) return McpSeedPlan(emptyList(), markSeeded = true)
        return McpSeedPlan(catalog(), markSeeded = true)
    }

    fun catalog(): List<McpServerEntity> {
        val fetch = McpPresets.all.single { it.id == "fetch" }
        val time = McpPresets.all.single { it.id == "time" }
        return listOf(
            McpServerEntity(
                name = "github",
                url = "https://api.githubcopilot.com/mcp",
                bearerToken = "",
                headersJson = "[]",
                enabled = true
            ),
            McpServerEntity(
                name = "searxng",
                url = "http://${McpPresets.LAN_HOST}:8088/mcp",
                bearerToken = "",
                headersJson = "[]",
                enabled = true
            ),
            fetch.serverFor(fetch.defaultUrl)!!.copy(enabled = true),
            time.serverFor(time.defaultUrl)!!.copy(enabled = true)
        )
    }
}

/** True when this preset is already saved, by its name or its default URL. */
fun McpPreset.isAlreadySaved(servers: List<McpServerEntity>): Boolean {
    val url = defaultUrl.trim().trimEnd('/')
    return servers.any { server ->
        server.name.equals(name, ignoreCase = true) ||
            server.url.trim().trimEnd('/').equals(url, ignoreCase = true)
    }
}

/** First launch only. Existing rows are kept, and a later empty table stays empty. */
fun seedDefaultMcpServers(context: Context) {
    val prefs = context.getSharedPreferences(DefaultMcpServers.PREFS_NAME, Context.MODE_PRIVATE)
    val alreadySeeded = prefs.getBoolean(DefaultMcpServers.KEY_SEEDED, false)
    val count = runBlocking(Dispatchers.IO) { Db.mcp.count() }
    val plan = DefaultMcpServers.plan(count, alreadySeeded)
    if (plan.insert.isNotEmpty()) {
        runBlocking(Dispatchers.IO) {
            plan.insert.forEach { Db.mcp.upsert(it) }
        }
    }
    if (plan.markSeeded) {
        prefs.edit().putBoolean(DefaultMcpServers.KEY_SEEDED, true).apply()
    }
}
