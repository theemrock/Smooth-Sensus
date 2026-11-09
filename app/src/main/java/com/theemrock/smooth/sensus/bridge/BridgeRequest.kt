package com.theemrock.smooth.sensus.bridge

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.theemrock.smooth.bridge.SmoothBridge
import com.theemrock.smooth.sensus.inference.InferenceChatMessage
import java.io.StringReader

sealed interface BridgeRequest {
    val maxTokens: Int

    data class Structured(val command: String, val schema: JsonObject, override val maxTokens: Int) : BridgeRequest
    data class Generate(val messages: List<InferenceChatMessage>, override val maxTokens: Int) : BridgeRequest
}

/** Messages are fixed strings; request content is never echoed into errors. */
class BridgeRequestException(val code: String, message: String) : Exception(message)

private val jsonElementAdapter = Gson().getAdapter(JsonElement::class.java)

private const val MAX_REQUEST_CHARS = 65_536
private const val MAX_COMMAND_CHARS = 4_000
private const val MAX_MESSAGES = 32
private const val MAX_TOKENS_LIMIT = 2_048
private const val DEFAULT_MAX_TOKENS = 256
private val ROLES = setOf("system", "user", "assistant")

/** Strict JSON parse of a whole document; null for anything malformed, truncated or followed by trailing text. */
internal fun parseStrictJson(text: String): JsonElement? = try {
    val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
    val element = jsonElementAdapter.read(reader)
    if (reader.peek() == JsonToken.END_DOCUMENT) element else null
} catch (_: Exception) {
    null
}

fun parseBridgeRequest(json: String): BridgeRequest {
    if (json.length > MAX_REQUEST_CHARS) throw invalid("Request too large")
    val root = parseStrictJson(json) as? JsonObject ?: throw invalid("Request must be a JSON object")
    val capability = root.string("capability") ?: throw invalid("capability is required")
    val maxTokens = when (val raw = root.get("maxTokens")) {
        null -> DEFAULT_MAX_TOKENS
        else -> raw.asExactInt()?.takeIf { it in 1..MAX_TOKENS_LIMIT } ?: throw invalid("maxTokens out of range")
    }
    return when (capability) {
        SmoothBridge.CAPABILITY_STRUCTURED -> {
            val command = root.string("command")?.takeIf { it.isNotBlank() && it.length <= MAX_COMMAND_CHARS }
                ?: throw invalid("command is invalid")
            val schema = root.get("schema") as? JsonObject ?: throw invalid("schema must be an object")
            BridgeRequest.Structured(command, schema, maxTokens)
        }
        SmoothBridge.CAPABILITY_GENERATE -> {
            val array = root.get("messages") as? JsonArray
            if (array == null || array.size() == 0 || array.size() > MAX_MESSAGES) throw invalid("messages are invalid")
            val messages = array.map { item ->
                val message = item as? JsonObject ?: throw invalid("message must be an object")
                val role = message.string("role")?.takeIf { it in ROLES } ?: throw invalid("message role is invalid")
                val content = message.string("content")?.takeIf { it.isNotBlank() } ?: throw invalid("message content is invalid")
                InferenceChatMessage(role, content)
            }
            BridgeRequest.Generate(messages, maxTokens)
        }
        else -> throw BridgeRequestException(SmoothBridge.Error.UNSUPPORTED_CAPABILITY, "Unsupported capability")
    }
}

private fun invalid(message: String) = BridgeRequestException(SmoothBridge.Error.INVALID_REQUEST, message)

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.asString

private fun JsonElement.asExactInt(): Int? =
    (this as? JsonPrimitive)?.takeIf { it.isNumber }?.let { runCatching { it.asBigDecimal.intValueExact() }.getOrNull() }
