package com.theemrock.smooth.sensus.inference

/**
 * Backend-neutral contract for a loaded local inference engine.
 *
 * Backend selection, multi-model management and session/KV ownership are outside this
 * boundary for now. Backends without incremental generation support retain compatibility
 * through the single-chunk default implementation.
 */
interface RuntimeBackend : AutoCloseable {
    suspend fun initialize()

    suspend fun generate(
        prompt: String,
        grammar: String? = null,
        enableThinking: Boolean = false
    ): InferenceResult

    suspend fun generateChatStreaming(
        messages: List<InferenceChatMessage>,
        enableThinking: Boolean = false,
        maxTokens: Int,
        onChunk: InferenceTextChunkCallback
    ): InferenceResult {
        val result = generateChat(messages, enableThinking, maxTokens)
        if (result.text.isNotEmpty()) onChunk.onTextChunk(result.text)
        return result
    }

    /**
     * Requests cancellation without waiting for the active generation lock.
     * Returns true only when the backend accepted the request.
     */
    fun cancelGeneration(): Boolean = false

    suspend fun generateWithMaxTokens(
        prompt: String,
        grammar: String? = null,
        enableThinking: Boolean = false,
        maxTokens: Int
    ): InferenceResult = generate(prompt, grammar, enableThinking)

    suspend fun generateChat(
        messages: List<InferenceChatMessage>,
        enableThinking: Boolean = false,
        maxTokens: Int
    ): InferenceResult = generate(messages.last().content, enableThinking = enableThinking)
}
