package com.theemrock.smooth.sensus.inference

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlamaCppInferenceEngine internal constructor(
    private val config: LlamaCppConfig,
    private val nativeBridge: LlamaCppNativeBridge
) : RuntimeBackend {
    constructor(config: LlamaCppConfig) : this(config, JniLlamaCppNativeBridge)

    private val operationLock = ReentrantLock()
    private val nativeHandleLock = ReentrantLock()
    private var nativeHandle = 0L
    private var closed = false

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        operationLock.withLock {
            check(!closed) { "Inference engine is closed" }
            if (nativeHandle != 0L) return@withLock
            if (!File(config.modelPath).isFile) {
                throw LocalInferenceException.ModelNotFound()
            }
            val loadedHandle = nativeBridge.load(config)
            if (loadedHandle == 0L) {
                throw LocalInferenceException.NativeFailure("Native model initialization failed")
            }
            nativeHandleLock.withLock { nativeHandle = loadedHandle }
        }
    }

    override suspend fun generate(
        prompt: String,
        grammar: String?,
        enableThinking: Boolean
    ): InferenceResult =
        withContext(Dispatchers.IO) {
            operationLock.withLock {
                check(!closed) { "Inference engine is closed" }
                check(nativeHandle != 0L) { "Inference engine is not initialized" }
                nativeBridge.generateWithMaxTokens(nativeHandle, prompt, grammar, enableThinking, config.maxTokens)
            }
        }

    override suspend fun generateWithMaxTokens(prompt: String, grammar: String?, enableThinking: Boolean, maxTokens: Int): InferenceResult = withContext(Dispatchers.IO) {
        require(maxTokens > 0) { "maxTokens must be positive" }
        operationLock.withLock { check(!closed); check(nativeHandle != 0L); nativeBridge.generateWithMaxTokens(nativeHandle, prompt, grammar, enableThinking, maxTokens) }
    }

    override suspend fun generateChat(messages: List<InferenceChatMessage>, enableThinking: Boolean, maxTokens: Int): InferenceResult =
        withContext(Dispatchers.IO) {
            require(messages.isNotEmpty()) { "Chat messages must not be empty" }
            require(maxTokens > 0) { "maxTokens must be positive" }
            operationLock.withLock {
                check(!closed) { "Inference engine is closed" }
                check(nativeHandle != 0L) { "Inference engine is not initialized" }
                nativeBridge.generateChat(nativeHandle, messages, enableThinking, maxTokens)
            }
        }

    override suspend fun generateChatStreaming(
        messages: List<InferenceChatMessage>,
        enableThinking: Boolean,
        maxTokens: Int,
        onChunk: InferenceTextChunkCallback
    ): InferenceResult = withContext(Dispatchers.IO) {
        require(messages.isNotEmpty()) { "Chat messages must not be empty" }
        require(maxTokens > 0) { "maxTokens must be positive" }
        operationLock.withLock {
            check(!closed) { "Inference engine is closed" }
            check(nativeHandle != 0L) { "Inference engine is not initialized" }
            nativeBridge.generateChatStreaming(
                nativeHandle,
                messages,
                enableThinking,
                maxTokens,
                onChunk
            )
        }
    }

    override fun cancelGeneration(): Boolean = nativeHandleLock.withLock {
        val handle = nativeHandle
        handle != 0L && nativeBridge.cancelGeneration(handle)
    }

    override fun close() {
        operationLock.withLock {
            if (closed) return
            val handle = nativeHandleLock.withLock {
                closed = true
                nativeHandle.also { nativeHandle = 0L }
            }
            if (handle != 0L) {
                nativeBridge.release(handle)
            }
        }
    }
}

internal interface LlamaCppNativeBridge {
    fun load(config: LlamaCppConfig): Long
    fun generate(handle: Long, prompt: String, grammar: String?, enableThinking: Boolean): InferenceResult
    fun generateWithMaxTokens(handle: Long, prompt: String, grammar: String?, enableThinking: Boolean, maxTokens: Int): InferenceResult = generate(handle, prompt, grammar, enableThinking)
    fun generateChat(handle: Long, messages: List<InferenceChatMessage>, enableThinking: Boolean, maxTokens: Int): InferenceResult =
        generateWithMaxTokens(handle, messages.last().content, null, enableThinking, maxTokens)
    fun generateChatStreaming(
        handle: Long,
        messages: List<InferenceChatMessage>,
        enableThinking: Boolean,
        maxTokens: Int,
        onChunk: InferenceTextChunkCallback
    ): InferenceResult {
        val result = generateChat(handle, messages, enableThinking, maxTokens)
        if (result.text.isNotEmpty()) onChunk.onTextChunk(result.text)
        return result
    }
    fun cancelGeneration(handle: Long): Boolean = false
    fun release(handle: Long)
}

private object JniLlamaCppNativeBridge : LlamaCppNativeBridge {
    init {
        System.loadLibrary("smooth_llama")
    }

    override fun load(config: LlamaCppConfig): Long = nativeLoad(
        modelPath = config.modelPath,
        contextSize = config.contextSize,
        threadCount = config.threadCount,
        maxTokens = config.maxTokens,
        temperature = config.temperature,
        topP = config.topP,
        topK = config.topK,
        seed = config.seed,
        useJinjaTemplate = config.useJinjaTemplate
    )

    override fun generate(handle: Long, prompt: String, grammar: String?, enableThinking: Boolean): InferenceResult =
        nativeGenerate(handle, prompt, grammar, enableThinking, 64)

    override fun generateWithMaxTokens(handle: Long, prompt: String, grammar: String?, enableThinking: Boolean, maxTokens: Int): InferenceResult =
        nativeGenerate(handle, prompt, grammar, enableThinking, maxTokens)

    override fun generateChat(handle: Long, messages: List<InferenceChatMessage>, enableThinking: Boolean, maxTokens: Int): InferenceResult =
        nativeGenerateChat(handle, messages.map { it.role }.toTypedArray(), messages.map { it.content }.toTypedArray(), enableThinking, maxTokens)

    override fun generateChatStreaming(
        handle: Long,
        messages: List<InferenceChatMessage>,
        enableThinking: Boolean,
        maxTokens: Int,
        onChunk: InferenceTextChunkCallback
    ): InferenceResult = nativeGenerateChatStreaming(
        handle,
        messages.map { it.role }.toTypedArray(),
        messages.map { it.content }.toTypedArray(),
        enableThinking,
        maxTokens,
        onChunk
    )

    override fun cancelGeneration(handle: Long): Boolean = nativeCancelGeneration(handle)

    override fun release(handle: Long) = nativeRelease(handle)

    private external fun nativeLoad(
        modelPath: String,
        contextSize: Int,
        threadCount: Int,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Int,
        useJinjaTemplate: Boolean
    ): Long

    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        grammar: String?,
        enableThinking: Boolean,
        maxTokens: Int
    ): InferenceResult
    private external fun nativeGenerateChat(handle: Long, roles: Array<String>, contents: Array<String>, enableThinking: Boolean, maxTokens: Int): InferenceResult
    private external fun nativeGenerateChatStreaming(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        enableThinking: Boolean,
        maxTokens: Int,
        onChunk: InferenceTextChunkCallback
    ): InferenceResult
    private external fun nativeCancelGeneration(handle: Long): Boolean
    private external fun nativeRelease(handle: Long)
}
