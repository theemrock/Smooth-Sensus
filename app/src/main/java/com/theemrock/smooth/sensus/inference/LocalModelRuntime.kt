package com.theemrock.smooth.sensus.inference

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ActiveModel(
    val displayName: String,
    val sizeBytes: Long
)

fun interface RuntimeBackendFactory {
    fun create(config: LlamaCppConfig): RuntimeBackend
}

object LlamaCppInferenceEngineFactory : RuntimeBackendFactory {
    override fun create(config: LlamaCppConfig): RuntimeBackend =
        LlamaCppInferenceEngine(config)
}

interface LocalModelRuntimeBoundary {
    suspend fun switchTo(storedModel: StoredModel): ActiveModel
    suspend fun generate(prompt: String, grammar: String? = null, enableThinking: Boolean = false): InferenceResult
    suspend fun generateChatStreaming(
        messages: List<InferenceChatMessage>,
        enableThinking: Boolean = false,
        maxTokens: Int = 256,
        onChunk: InferenceTextChunkCallback
    ): InferenceResult {
        val result = generateChat(messages, enableThinking, maxTokens)
        if (result.text.isNotEmpty()) onChunk.onTextChunk(result.text)
        return result
    }
    fun cancelGeneration(): Boolean = false
    suspend fun generateWithMaxTokens(prompt: String, grammar: String? = null, enableThinking: Boolean = false, maxTokens: Int): InferenceResult = generate(prompt, grammar, enableThinking)
    suspend fun generateChat(messages: List<InferenceChatMessage>, enableThinking: Boolean = false, maxTokens: Int = 256): InferenceResult =
        generate(messages.last().content, enableThinking = enableThinking)
    suspend fun unload()
    fun activeModel(): ActiveModel?
}

class LocalModelRuntime(
    private val engineFactory: RuntimeBackendFactory = LlamaCppInferenceEngineFactory
) : LocalModelRuntimeBoundary {
    private val operationMutex = Mutex()

    @Volatile
    private var engine: RuntimeBackend? = null

    @Volatile
    private var active: ActiveModel? = null

    override suspend fun switchTo(storedModel: StoredModel): ActiveModel = operationMutex.withLock {
        engine?.close()
        engine = null
        active = null

        val candidate = engineFactory.create(smokeConfig(storedModel))
        try {
            candidate.initialize()
            val selected = ActiveModel(storedModel.displayName, storedModel.sizeBytes)
            engine = candidate
            active = selected
            selected
        } catch (failure: Throwable) {
            candidate.close()
            throw failure
        }
    }

    override suspend fun generate(prompt: String, grammar: String?, enableThinking: Boolean): InferenceResult =
        operationMutex.withLock {
            val current = checkNotNull(engine) { "No local model is active" }
            current.generate(prompt, grammar, enableThinking)
        }

    override suspend fun generateChatStreaming(
        messages: List<InferenceChatMessage>,
        enableThinking: Boolean,
        maxTokens: Int,
        onChunk: InferenceTextChunkCallback
    ): InferenceResult = operationMutex.withLock {
        val current = checkNotNull(engine) { "No local model is active" }
        current.generateChatStreaming(messages, enableThinking, maxTokens, onChunk)
    }

    // Deliberately bypasses operationMutex so cancellation can reach an active generation.
    override fun cancelGeneration(): Boolean = engine?.cancelGeneration() ?: false

    override suspend fun generateChat(messages: List<InferenceChatMessage>, enableThinking: Boolean, maxTokens: Int): InferenceResult = operationMutex.withLock {
        val current = checkNotNull(engine) { "Etkin yerel model yok" }
        current.generateChat(messages, enableThinking, maxTokens)
    }

    override suspend fun generateWithMaxTokens(prompt: String, grammar: String?, enableThinking: Boolean, maxTokens: Int): InferenceResult = operationMutex.withLock {
        val current = checkNotNull(engine) { "Etkin yerel model yok" }
        current.generateWithMaxTokens(prompt, grammar, enableThinking, maxTokens)
    }

    override suspend fun unload() = operationMutex.withLock {
        engine?.close()
        engine = null
        active = null
    }

    override fun activeModel(): ActiveModel? = active

    private fun smokeConfig(storedModel: StoredModel) = LlamaCppConfig(
        modelPath = storedModel.file.absolutePath,
        contextSize = 4096,
        threadCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        maxTokens = 64,
        temperature = 0.0f,
        seed = 0
    )
}
