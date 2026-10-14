package com.theemrock.smooth.sensus.inference

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppInferenceEngineTest {
    @Test
    fun `initialize loads once and repeated generation reuses the native handle`() = runBlocking {
        val model = Files.createTempFile("model", ".gguf").toFile()
        val native = RecordingNativeBridge()
        val engine: RuntimeBackend =
            LlamaCppInferenceEngine(LlamaCppConfig(model.absolutePath), native)

        engine.initialize()
        engine.initialize()
        assertEquals("raw:first", engine.generate("first").text)
        val second = engine.generate("second", "root ::= \"ok\"", enableThinking = true)
        assertEquals("raw:second", second.text)
        assertEquals(1500.0, second.metrics.promptTokensPerSecond, 0.001)
        assertEquals(1000.0, second.metrics.generationTokensPerSecond, 0.001)
        assertEquals(InferenceTerminationReason.EOG, second.metrics.terminationReason)

        assertEquals(1, native.loadCount)
        assertEquals(listOf(null, "root ::= \"ok\""), native.grammars)
        assertEquals(listOf(false, true), native.thinking)
        engine.close()
        assertEquals(1, native.releaseCount)
    }

    @Test
    fun `missing model is rejected before native load`() {
        val native = RecordingNativeBridge()
        val engine = LlamaCppInferenceEngine(
            LlamaCppConfig("missing-${System.nanoTime()}.gguf"),
            native
        )

        assertThrows(LocalInferenceException.ModelNotFound::class.java) {
            runBlocking { engine.initialize() }
        }
        assertEquals(0, native.loadCount)
    }

    @Test
    fun `generation before initialize is rejected without native call`() {
        val native = RecordingNativeBridge()
        val engine = LlamaCppInferenceEngine(LlamaCppConfig("unused.gguf"), native)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.generate("prompt") }
        }
        assertEquals(0, native.generateCount)
    }

    @Test
    fun `generation after close is rejected and release is idempotent`() = runBlocking {
        val model = Files.createTempFile("model", ".gguf").toFile()
        val native = RecordingNativeBridge()
        val engine = LlamaCppInferenceEngine(LlamaCppConfig(model.absolutePath), native)
        engine.initialize()

        engine.close()
        engine.close()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.generate("prompt") }
        }
        assertEquals(1, native.releaseCount)
    }

    @Test
    fun `chat streaming forwards multiple chunks and preserves final result`() = runBlocking {
        val model = Files.createTempFile("model", ".gguf").toFile()
        val expected = InferenceResult(
            text = "Hello, dünya!",
            metrics = InferenceMetrics(
                modelLoadDurationMs = 5.0,
                promptTokenCount = 7,
                promptEvalDurationMs = 2.5,
                timeToFirstTokenMs = 4.5,
                generatedTokenCount = 4,
                generationDurationMs = 6.5,
                totalInferenceDurationMs = 9.0,
                terminationReason = InferenceTerminationReason.EOG,
                templateApplied = true,
                contextUsed = 11,
                contextMax = 32
            )
        )
        val native = RecordingNativeBridge(
            streamingChunks = listOf("Hello", ", ", "dün", "ya!"),
            streamingResult = expected
        )
        val engine: RuntimeBackend = LlamaCppInferenceEngine(
            LlamaCppConfig(model.absolutePath),
            native
        )
        engine.initialize()
        val chunks = mutableListOf<String>()
        val messages = listOf(
            InferenceChatMessage("user", "Merhaba"),
            InferenceChatMessage("assistant", "Hello")
        )

        val result = engine.generateChatStreaming(
            messages = messages,
            enableThinking = true,
            maxTokens = 48
        ) { chunk -> chunks += chunk }

        assertEquals(listOf("Hello", ", ", "dün", "ya!"), chunks)
        assertEquals(result.text, chunks.joinToString(separator = ""))
        assertEquals(expected, result)
        assertEquals(messages, native.streamingMessages)
        assertEquals(true, native.streamingEnableThinking)
        assertEquals(48, native.streamingMaxTokens)
    }

    @Test
    fun `empty streaming result emits no text chunk`() = runBlocking {
        val model = Files.createTempFile("model", ".gguf").toFile()
        val expected = inferenceResult(text = "")
        val engine: RuntimeBackend = LlamaCppInferenceEngine(
            LlamaCppConfig(model.absolutePath),
            RecordingNativeBridge(streamingChunks = emptyList(), streamingResult = expected)
        )
        engine.initialize()
        val chunks = mutableListOf<String>()

        val result = engine.generateChatStreaming(
            messages = listOf(InferenceChatMessage("user", "prompt")),
            maxTokens = 32
        ) { chunk -> chunks += chunk }

        assertEquals(emptyList<String>(), chunks)
        assertEquals(expected, result)
    }

    @Test
    fun `streaming callback failure is propagated and stops later chunks`() {
        val model = Files.createTempFile("model", ".gguf").toFile()
        val callbackFailure = IllegalStateException("callback failed")
        val engine: RuntimeBackend = LlamaCppInferenceEngine(
            LlamaCppConfig(model.absolutePath),
            RecordingNativeBridge(
                streamingChunks = listOf("first", "second", "third"),
                streamingResult = inferenceResult("firstsecondthird")
            )
        )
        runBlocking { engine.initialize() }
        val chunks = mutableListOf<String>()

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                engine.generateChatStreaming(
                    messages = listOf(InferenceChatMessage("user", "prompt")),
                    maxTokens = 32
                ) { chunk ->
                    chunks += chunk
                    if (chunk == "second") throw callbackFailure
                }
            }
        }

        assertTrue(thrown === callbackFailure || thrown.cause === callbackFailure)
        assertEquals(listOf("first", "second"), chunks)
    }

    @Test
    fun `cancellation before initialize returns false without a native call`() {
        val engine: RuntimeBackend = LlamaCppInferenceEngine(
            LlamaCppConfig("unused.gguf"),
            RecordingNativeBridge()
        )

        assertFalse(engine.cancelGeneration())
    }

    @Test
    fun `active cancellation bypasses generation lock preserves partial output and allows recovery`() = runBlocking {
        val model = Files.createTempFile("model", ".gguf").toFile()
        val native = CancellableNativeBridge()
        val engine: RuntimeBackend = LlamaCppInferenceEngine(
            LlamaCppConfig(model.absolutePath),
            native
        )
        engine.initialize()
        assertFalse(engine.cancelGeneration())
        val chunks = mutableListOf<String>()

        val generation = async(Dispatchers.Default) {
            engine.generateChatStreaming(
                messages = listOf(InferenceChatMessage("user", "keep generating")),
                maxTokens = 128
            ) { chunk -> chunks += chunk }
        }
        assertTrue(native.generationStarted.await(2, TimeUnit.SECONDS))

        try {
            assertTrue(withTimeout(2_000) { async(Dispatchers.Default) { engine.cancelGeneration() }.await() })
            assertTrue(engine.cancelGeneration())
        } finally {
            native.allowGenerationToFinish.countDown()
        }

        val cancelled = withTimeout(2_000) { generation.await() }
        assertEquals(InferenceTerminationReason.CANCELLED, cancelled.metrics.terminationReason)
        assertEquals(listOf("par", "tial"), chunks)
        assertEquals("partial", cancelled.text)
        assertEquals(cancelled.text, chunks.joinToString(separator = ""))
        assertEquals(2, native.cancelCount)
        assertFalse(engine.cancelGeneration())

        val recovered = engine.generateChat(
            messages = listOf(InferenceChatMessage("user", "recover")),
            maxTokens = 8
        )
        assertEquals("recovered", recovered.text)
        assertEquals(InferenceTerminationReason.EOG, recovered.metrics.terminationReason)
        assertEquals(1, native.loadCount)
    }

    @Test
    fun `close does not release native handle while cancellation call is using it`() = runBlocking {
        val model = Files.createTempFile("model", ".gguf").toFile()
        val native = BlockingCancellationNativeBridge()
        val engine = LlamaCppInferenceEngine(LlamaCppConfig(model.absolutePath), native)
        engine.initialize()

        val cancellation = async(Dispatchers.Default) { engine.cancelGeneration() }
        assertTrue(native.cancellationEntered.await(2, TimeUnit.SECONDS))
        val close = async(Dispatchers.Default) { engine.close() }

        assertFalse(native.releaseCalled.await(200, TimeUnit.MILLISECONDS))
        native.allowCancellationToReturn.countDown()
        assertFalse(withTimeout(2_000) { cancellation.await() })
        withTimeout(2_000) { close.await() }
        assertEquals(1, native.releaseCount)
    }

    private class RecordingNativeBridge(
        private val streamingChunks: List<String>? = null,
        private val streamingResult: InferenceResult? = null
    ) : LlamaCppNativeBridge {
        var loadCount = 0
        var generateCount = 0
        var releaseCount = 0
        val grammars = mutableListOf<String?>()
        var streamingMessages: List<InferenceChatMessage>? = null
        var streamingEnableThinking: Boolean? = null
        var streamingMaxTokens: Int? = null

        override fun load(config: LlamaCppConfig): Long {
            loadCount++
            return 7L
        }

        val thinking = mutableListOf<Boolean>()

        override fun generate(
            handle: Long,
            prompt: String,
            grammar: String?,
            enableThinking: Boolean
        ): InferenceResult {
            generateCount++
            grammars += grammar
            thinking += enableThinking
            return streamingResult ?: inferenceResult(text = "raw:$prompt")
        }

        override fun generateChatStreaming(
            handle: Long,
            messages: List<InferenceChatMessage>,
            enableThinking: Boolean,
            maxTokens: Int,
            onChunk: InferenceTextChunkCallback
        ): InferenceResult {
            streamingMessages = messages
            streamingEnableThinking = enableThinking
            streamingMaxTokens = maxTokens
            val result = streamingResult ?: inferenceResult("raw:${messages.last().content}")
            (streamingChunks ?: listOf(result.text)).forEach { chunk ->
                if (chunk.isNotEmpty()) onChunk.onTextChunk(chunk)
            }
            return result
        }

        override fun release(handle: Long) {
            releaseCount++
        }
    }

    private class CancellableNativeBridge : LlamaCppNativeBridge {
        val generationStarted = CountDownLatch(1)
        val allowGenerationToFinish = CountDownLatch(1)
        private val generationActive = AtomicBoolean(false)
        private val cancellationRequested = AtomicBoolean(false)
        var loadCount = 0
        var cancelCount = 0

        override fun load(config: LlamaCppConfig): Long {
            loadCount++
            return 11L
        }

        override fun generate(
            handle: Long,
            prompt: String,
            grammar: String?,
            enableThinking: Boolean
        ): InferenceResult = inferenceResult("recovered")

        override fun generateChatStreaming(
            handle: Long,
            messages: List<InferenceChatMessage>,
            enableThinking: Boolean,
            maxTokens: Int,
            onChunk: InferenceTextChunkCallback
        ): InferenceResult {
            generationActive.set(true)
            return try {
                onChunk.onTextChunk("par")
                onChunk.onTextChunk("tial")
                generationStarted.countDown()
                check(allowGenerationToFinish.await(2, TimeUnit.SECONDS))
                InferenceResult(
                    text = "partial",
                    metrics = inferenceResult("partial").metrics.copy(
                        generatedTokenCount = 2,
                        terminationReason = if (cancellationRequested.get()) {
                            InferenceTerminationReason.CANCELLED
                        } else {
                            InferenceTerminationReason.EOG
                        }
                    )
                )
            } finally {
                generationActive.set(false)
            }
        }

        override fun cancelGeneration(handle: Long): Boolean {
            if (!generationActive.get()) return false
            cancelCount++
            cancellationRequested.set(true)
            return true
        }

        override fun release(handle: Long) = Unit
    }

    private class BlockingCancellationNativeBridge : LlamaCppNativeBridge {
        val cancellationEntered = CountDownLatch(1)
        val allowCancellationToReturn = CountDownLatch(1)
        val releaseCalled = CountDownLatch(1)
        var releaseCount = 0

        override fun load(config: LlamaCppConfig): Long = 13L

        override fun generate(
            handle: Long,
            prompt: String,
            grammar: String?,
            enableThinking: Boolean
        ): InferenceResult = inferenceResult(prompt)

        override fun cancelGeneration(handle: Long): Boolean {
            cancellationEntered.countDown()
            check(allowCancellationToReturn.await(2, TimeUnit.SECONDS))
            return false
        }

        override fun release(handle: Long) {
            releaseCount++
            releaseCalled.countDown()
        }
    }

    companion object {
        private fun inferenceResult(text: String): InferenceResult = InferenceResult(
            text = text,
            metrics = InferenceMetrics(
                modelLoadDurationMs = 5.0,
                promptTokenCount = 3,
                promptEvalDurationMs = 2.0,
                timeToFirstTokenMs = 4.0,
                generatedTokenCount = 1,
                generationDurationMs = 1.0,
                totalInferenceDurationMs = 5.0,
                terminationReason = InferenceTerminationReason.EOG,
                contextUsed = 3,
                contextMax = 8
            )
        )
    }
}
