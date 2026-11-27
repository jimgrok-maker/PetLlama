package com.jimgrok.petllama.ui.mcp

import com.jimgrok.petllama.data.McpServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMcpServersTest {

    @Test
    fun emptyTableOnFirstRunInsertsTheFourServersEnabled() {
        val plan = DefaultMcpServers.plan(existingCount = 0, alreadySeeded = false)
        assertTrue(plan.markSeeded)
        assertEquals(listOf("github", "searxng", "Fetch", "Time"), plan.insert.map { it.name })
        assertTrue(plan.insert.all { it.enabled && it.bearerToken.isEmpty() && it.id == 0L })
        val fetch = McpPresets.all.single { it.id == "fetch" }
        val time = McpPresets.all.single { it.id == "time" }
        assertEquals("https://api.githubcopilot.com/mcp", plan.insert[0].url)
        assertEquals("http://192.168.1.250:8088/mcp", plan.insert[1].url)
        assertEquals(fetch.defaultUrl, plan.insert[2].url)
        assertEquals(fetch.description, McpPresets.all.single { it.name == plan.insert[2].name }.description)
        assertEquals(time.defaultUrl, plan.insert[3].url)
        assertTrue(plan.insert.none { it.bearerToken.isNotEmpty() || it.url.contains("ghp_") || it.url.contains("token") })
    }

    @Test
    fun existingRowsAreLeftAloneAndALaterEmptyTableIsNotRefilled() {
        val existing = DefaultMcpServers.plan(existingCount = 2, alreadySeeded = false)
        assertTrue(existing.insert.isEmpty())
        assertTrue(existing.markSeeded)

        val deleted = DefaultMcpServers.plan(existingCount = 0, alreadySeeded = true)
        assertTrue(deleted.insert.isEmpty())
        assertFalse(deleted.markSeeded)
    }

    @Test
    fun aSavedPresetIsNotOfferedAgain() {
        val fetch = McpPresets.all.single { it.id == "fetch" }
        val saved = listOf(
            McpServerEntity(id = 3, name = "Fetch", url = fetch.defaultUrl, enabled = true)
        )
        assertTrue(fetch.isAlreadySaved(saved))
        assertFalse(McpPresets.all.single { it.id == "filesystem" }.isAlreadySaved(saved))
        assertTrue(fetch.isAlreadySaved(listOf(McpServerEntity(name = "other", url = fetch.defaultUrl + "/"))))
    }
}
