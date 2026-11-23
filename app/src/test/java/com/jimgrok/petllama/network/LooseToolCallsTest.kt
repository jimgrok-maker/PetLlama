package com.jimgrok.petllama.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jimgrok.petllama.data.McpServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LooseToolCallsTest {

    @Test
    fun qwenXmlWithoutClosingTagsBecomesToolCalls() {
        val text = """
            Looking at the tree.
            <tool_call> <function=s2_get_file_contents> <parameter=owner> jimgrok-maker <parameter=repo> PetLlama <parameter=path> app/src/main/java/com/jimgrok/petllama/data </tool_call>
            <tool_call> <function=s2_get_file_contents> <parameter=owner> jimgrok-maker <parameter=repo> PetLlama <parameter=path> app/src/main/java/com/jimgrok/petllama/network </tool_call>
        """.trimIndent()
        val parsed = parseLooseToolCalls(text)
        assertEquals("Looking at the tree.", parsed.prose)
        assertEquals(2, parsed.calls.size)
        val args = JsonParser.parseString(parsed.calls[0].arguments).asJsonObject
        assertEquals("jimgrok-maker", args.get("owner").asString)
        assertEquals("PetLlama", args.get("repo").asString)
        assertEquals("app/src/main/java/com/jimgrok/petllama/data", args.get("path").asString)
    }

    @Test
    fun reasoningOnlyCallIsRecoveredWhenTheAnswerIsEmpty() {
        val reasoning = """
            <tool_call> <function=s2_get_file_contents> <parameter=owner> jimgrok-maker <parameter=repo> PetLlama <parameter=path> app/src/main <parameter=sha> 3a9dc79b617d <parameter=fields> ["name","type","path"] </tool_call>
        """.trimIndent()
        val recovered = recoverLooseCalls(content = "", reasoning = reasoning)
        assertEquals("", recovered.answer)
        assertFalse(recovered.reasoning.contains("<tool_call"))
        assertEquals("s2_get_file_contents", recovered.calls.single().name)
        val args = JsonParser.parseString(recovered.calls.single().arguments).asJsonObject
        assertEquals("app/src/main", args.get("path").asString)
        assertEquals("3a9dc79b617d", args.get("sha").asString)
        assertEquals("""["name","type","path"]""", args.get("fields").asString)
        assertTrue(recoverLooseCalls("Here is the review.", reasoning).calls.isEmpty())
    }

    @Test
    fun shaMapsToRefAndUnknownFieldsAreDropped() {
        val schema = JsonParser.parseString(
            """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"},"path":{"type":"string"},"ref":{"type":"string"}}}"""
        ).asJsonObject
        val tool = McpTool("get_file_contents", "read", schema)
        val raw = """{"owner":"jimgrok-maker","repo":"PetLlama","path":"app/src/main","sha":"3a9dc79b617d","fields":"[\"name\",\"type\",\"path\"]"}"""
        val fitted = JsonParser.parseString(fitArguments(tool, raw)).asJsonObject
        assertEquals("3a9dc79b617d", fitted.get("ref").asString)
        assertFalse(fitted.has("sha"))
        assertFalse(fitted.has("fields"))
        assertEquals("app/src/main", fitted.get("path").asString)
    }

    @Test
    fun prefixedNameMapsBackToThatServer() {
        val github = McpServerEntity(id = 2, name = "github", url = "https://example/mcp")
        val other = McpServerEntity(id = 4, name = "other", url = "http://example/mcp")
        val read = McpTool("get_file_contents", "read a file", JsonObject())
        val catalog = ToolCatalog.from(listOf(github to listOf(read), other to listOf(read)))
        val match = catalog.findLoose("s2_get_file_contents")
        assertEquals(2L, match!!.server.id)
        assertEquals("get_file_contents", match.tool.name)
        assertNull(catalog.findLoose("get_file_contents"))
    }
}
