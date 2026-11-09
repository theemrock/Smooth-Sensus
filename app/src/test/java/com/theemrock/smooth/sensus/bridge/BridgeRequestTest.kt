package com.theemrock.smooth.sensus.bridge

import com.theemrock.smooth.bridge.SmoothBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRequestTest {
    private fun codeOf(json: String) = assertThrows(BridgeRequestException::class.java) { parseBridgeRequest(json) }.code

    @Test
    fun `valid structured request is parsed with default maxTokens`() {
        val request = parseBridgeRequest(
            """{"capability":"llm.structured","command":"yarın 7ye alarm kur","schema":{"type":"object"}}"""
        ) as BridgeRequest.Structured
        assertEquals("yarın 7ye alarm kur", request.command)
        assertEquals(256, request.maxTokens)
    }

    @Test
    fun `valid generate request keeps message order`() {
        val request = parseBridgeRequest(
            """{"capability":"llm.generate","maxTokens":64,"messages":[{"role":"system","content":"a"},{"role":"user","content":"b"}]}"""
        ) as BridgeRequest.Generate
        assertEquals(listOf("system", "user"), request.messages.map { it.role })
        assertEquals(64, request.maxTokens)
    }

    @Test
    fun `malformed or non-object input is INVALID_REQUEST`() {
        listOf("", "not json", "[]", "\"x\"", """{"capability":"llm.structured"} trailing""", """{"capability":"llm.generate""").forEach {
            assertEquals(it, SmoothBridge.Error.INVALID_REQUEST, codeOf(it))
        }
    }

    @Test
    fun `unknown capability is UNSUPPORTED_CAPABILITY`() {
        assertEquals(SmoothBridge.Error.UNSUPPORTED_CAPABILITY, codeOf("""{"capability":"vision.describe"}"""))
    }

    @Test
    fun `field validation failures are INVALID_REQUEST`() {
        val base = """"capability":"llm.structured","schema":{"type":"object"}"""
        listOf(
            "{$base}",
            """{$base,"command":"  "}""",
            """{$base,"command":"x","maxTokens":0}""",
            """{$base,"command":"x","maxTokens":99999}""",
            """{$base,"command":"x","maxTokens":1.5}""",
            """{"capability":"llm.structured","command":"x","schema":[]}""",
            """{"capability":"llm.generate","messages":[]}""",
            """{"capability":"llm.generate","messages":[{"role":"tool","content":"x"}]}"""
        ).forEach { assertEquals(it, SmoothBridge.Error.INVALID_REQUEST, codeOf(it)) }
    }

    @Test
    fun `strict parser rejects truncated JSON and accepts complete JSON`() {
        assertNull(parseStrictJson("""{"intent":"al"""))
        assertNull(parseStrictJson("""{"a":1}{"b":2}"""))
        assertTrue(parseStrictJson("""{"a":[1,2]}""")!!.isJsonObject)
    }
}
