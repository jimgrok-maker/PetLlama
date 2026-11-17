package com.jimgrok.petllama.ui.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpPresetsTest {

    @Test
    fun presetsAreFetchTimeAndFilesystemOnTheLanBox() {
        assertEquals(listOf("fetch", "time", "filesystem"), McpPresets.all.map { it.id })
        assertEquals(listOf("Fetch", "Time", "Filesystem"), McpPresets.all.map { it.name })
        assertEquals(
            listOf(
                "http://192.168.1.250:8089/mcp",
                "http://192.168.1.250:8090/mcp",
                "http://192.168.1.250:8091/mcp"
            ),
            McpPresets.all.map { it.defaultUrl }
        )
        McpPresets.all.forEach { preset ->
            assertTrue(preset.description.isNotBlank())
            assertTrue(preset.setupHelp.contains("docs/mcp-presets-compose.yml"))
            assertTrue(preset.setupHelp.contains("turned off"))
        }
    }

    @Test
    fun addCreatesADisabledServerAndLeavesOtherRowsAlone() {
        val preset = McpPresets.all.single { it.id == "fetch" }
        val server = preset.serverFor(preset.defaultUrl)!!
        assertEquals(0L, server.id)
        assertEquals("Fetch", server.name)
        assertEquals("http://192.168.1.250:8089/mcp", server.url)
        assertEquals("", server.bearerToken)
        assertEquals("[]", server.headersJson)
        assertEquals(false, server.enabled)
    }

    @Test
    fun editedUrlIsTrimmedAndABlankUrlIsNotSaved() {
        val preset = McpPresets.all.single { it.id == "filesystem" }
        val server = preset.serverFor("  http://10.0.0.8:8091/mcp  ")!!
        assertEquals("http://10.0.0.8:8091/mcp", server.url)
        assertEquals("Filesystem", server.name)
        assertEquals(false, server.enabled)
        assertNull(preset.serverFor("   "))
    }

    @Test
    fun eachPresetCanBeSavedOnItsOwn() {
        val saved = McpPresets.all.map { it.serverFor(it.defaultUrl)!! }
        assertEquals(3, saved.size)
        assertTrue(saved.all { !it.enabled && it.id == 0L && it.bearerToken.isEmpty() })
        assertEquals(saved.map { it.name }.toSet().size, saved.size)
    }
}
