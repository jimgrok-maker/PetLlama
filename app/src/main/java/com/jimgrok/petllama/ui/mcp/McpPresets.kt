package com.jimgrok.petllama.ui.mcp

import com.jimgrok.petllama.data.McpServerEntity

/**
 * One-tap MCP servers for the LAN box at [LAN_HOST].
 * Each preset is saved turned off. The URL can be changed before it is saved.
 * GitHub and SearXNG stay as the user typed them; they are not presets.
 */
data class McpPreset(
    val id: String,
    val name: String,
    val description: String,
    val defaultUrl: String,
    val setupHelp: String
) {
    /**
     * A new database row for this preset. [id] stays 0 so Room inserts a row
     * and does not replace a server that is already saved. Returns null when
     * [url] is blank.
     */
    fun serverFor(url: String): McpServerEntity? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return McpServerEntity(
            id = 0,
            name = name,
            url = trimmed,
            bearerToken = "",
            headersJson = "[]",
            enabled = false
        )
    }
}

object McpPresets {
    const val LAN_HOST = "192.168.1.250"

    val all: List<McpPreset> = listOf(
        McpPreset(
            id = "fetch",
            name = "Fetch",
            description = "Reads a web page and hands the text to the model.",
            defaultUrl = "http://$LAN_HOST:8089/mcp",
            setupHelp = "On 4650ge, start the Fetch service in docs/mcp-presets-compose.yml. " +
                "It speaks Streamable HTTP at http://$LAN_HOST:8089/mcp. " +
                "Add saves this server turned off. Tap Test, then flip the switch."
        ),
        McpPreset(
            id = "time",
            name = "Time",
            description = "Tells the model the current time. The server clock is New York.",
            defaultUrl = "http://$LAN_HOST:8090/mcp",
            setupHelp = "On 4650ge, start the Time service in docs/mcp-presets-compose.yml. " +
                "It listens at http://$LAN_HOST:8090/mcp with timezone America/New_York. " +
                "Add saves this server turned off. Tap Test, then flip the switch."
        ),
        McpPreset(
            id = "filesystem",
            name = "Filesystem",
            description = "Lets the model read one folder on 4650ge. Writing stays off until you change the server setup.",
            defaultUrl = "http://$LAN_HOST:8091/mcp",
            setupHelp = "On 4650ge, start the Filesystem service in docs/mcp-presets-compose.yml. " +
                "It listens at http://$LAN_HOST:8091/mcp and mounts a folder read-only. " +
                "Change that folder path in the compose file before the first start. " +
                "Add saves this server turned off. Tap Test, then flip the switch."
        )
    )
}
