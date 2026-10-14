package com.theemrock.smooth.sensus.inference

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `first switch initializes one engine with conservative smoke config`() = runBlocking {
        val factory = RecordingFactory()
        val runtime = LocalModelRuntime(factory)

        val active = runtime.switchTo(model("first.gguf"))

        assertEquals("first.gguf", active.displayName)
        assertEquals(1, factory.engines.single().initializeCount)
        with(factory.configs.single()) {
            assertEquals(4096, contextSize)
            assertEquals(64, maxTokens)
            assertEquals(0.0f, temperature)
            assertEquals(0, seed)
            assertTrue(threadCount in 1..4)
        }
    }

    @Test
    fun `multiple generations reuse the active engine`() = runBlocking {
        val factory = RecordingFactory()
        val runtime = LocalModelRuntime(factory)
        runtime.switchTo(model("first.gguf"))

        assertEquals("first:one:none", runtime.generate("one", null).text)
        assertEquals("first:two:root", runtime.generate("two", "root").text)

        assertEquals(1, factory.engines.size)
        assertEquals(2, factory.engines.single().generateCount)
    }

    @Test
    fun `successful switch closes old engine before initializing new one`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val factory = RecordingFactory(events = events)
        val runtime = LocalModelRuntime(factory)
        runtime.switchTo(model("old.gguf"))

        runtime.switchTo(model("new.gguf"))

        assertEquals(listOf("old:init", "old:close", "new:init"), events)
        assertEquals("new.gguf", runtime.activeModel()?.displayName)
    }

    @Test
    fun `failed switch closes candidate and leaves no active model without reloading old`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val factory = RecordingFactory(failNames = setOf("broken"), events = events)
        val runtime = LocalModelRuntime(factory)
        runtime.switchTo(model("old.gguf"))

        assertThrows(LocalInferenceException.NativeFailure::class.java) {
            runBlocking { runtime.switchTo(model("broken.gguf")) }
        }

        assertEquals(listOf("old:init", "old:close", "broken:init", "broken:close"), events)
        assertNull(runtime.activeModel())
        assertEquals(2, factory.engines.size)
    }

    @Test
    fun `unload is idempotent and generation without active model is rejected`() = runBlocking {
        val factory = RecordingFactory()
        val runtime = LocalModelRuntime(factory)
        runtime.switchTo(model("first.gguf"))

        runtime.unload()
        runtime.unload()

        assertEquals(1, factory.engines.single().closeCount)
        assertNull(runtime.activeModel())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { runtime.generate("prompt", null) }
        }
        Unit
    }

    @Test
    fun `concurrent generations are serialized`() = runBlocking {
        val concurrency = AtomicInteger()
        val maxConcurrency = AtomicInteger()
        val factory = RecordingFactory(concurrency = concurrency, maxConcurrency = maxConcurrency)
        val runtime = LocalModelRuntime(factory)
        runtime.switchTo(model("first.gguf"))

        val first = launch { runtime.generate("one", null) }
        val second = launch { runtime.generate("two", null) }
        first.join()
        second.join()

        assertEquals(1, maxConcurrency.get())
    }

    @Test
    fun `chat streaming generation is forwarded through the runtime boundary`() = runBlocking {
        val factory = RecordingFactory()
        val runtime: LocalModelRuntimeBoundary = LocalModelRuntime(factory)
        runtime.switchTo(model("first.gguf"))
        val chunks = mutableListOf<String>()

        val result = runtime.generateChatStreaming(
            messages = listOf(InferenceChatMessage("user", "prompt")),
            maxTokens = 32
        ) { chunk -> chunks += chunk }

        assertEquals(listOf("first:prompt:none"), chunks)
        assertEquals("first:prompt:none", result.text)
    }

    @Test
    fun `cancellation bypasses generation mutex and reaches active backend`() = runBlocking {
        val generationStarted = CompletableDeferred<Unit>()
        val generationRelease = CompletableDeferred<Unit>()
        val backend = BlockingEngine(generationStarted, generationRelease)
        val runtime: LocalModelRuntimeBoundary = LocalModelRuntime { backend }
        runtime.switchTo(model("first.gguf"))
        val generation = launch(Dispatchers.Default) { runtime.generate("prompt") }
        generationStarted.await()

        val cancellation = async(Dispatchers.Default) { runtime.cancelGeneration() }
        val accepted = withTimeoutOrNull(2_000) { cancellation.await() }

        generationRelease.complete(Unit)
        generation.join()
        assertEquals(true, accepted)
        assertEquals(1, backend.cancelCount)
    }

    private fun model(name: String): StoredModel {
        val file = temporaryFolder.newFile(name).apply { writeText("model") }
        return StoredModel(file, name, file.length())
    }

    private class RecordingFactory(
        private val failNames: Set<String> = emptySet(),
        private val events: MutableList<String> = mutableListOf(),
        private val concurrency: AtomicInteger = AtomicInteger(),
        private val maxConcurrency: AtomicInteger = AtomicInteger()
    ) : RuntimeBackendFactory {
        val configs = mutableListOf<LlamaCppConfig>()
        val engines = mutableListOf<RecordingEngine>()

        override fun create(config: LlamaCppConfig): RuntimeBackend {
            configs += config
            val name = File(config.modelPath).nameWithoutExtension
            return RecordingEngine(
                name,
                name in failNames,
                events,
                concurrency,
                maxConcurrency
            ).also(engines::add)
        }
    }

    private class RecordingEngine(
        private val name: String,
        private val failInitialize: Boolean,
        private val events: MutableList<String>,
        private val concurrency: AtomicInteger,
        private val maxConcurrency: AtomicInteger
    ) : RuntimeBackend {
        var initializeCount = 0
        var generateCount = 0
        var closeCount = 0

        override suspend fun initialize() {
            initializeCount++
            events += "$name:init"
            if (failInitialize) throw LocalInferenceException.NativeFailure("load failed")
        }

        override suspend fun generate(prompt: String, grammar: String?, enableThinking: Boolean): InferenceResult {
            generateCount++
            val active = concurrency.incrementAndGet()
            maxConcurrency.updateAndGet { current -> maxOf(current, active) }
            return try {
                delay(25)
                InferenceResult(
                    "$name:$prompt:${grammar ?: "none"}",
                    InferenceMetrics(0.0, 1, 1.0, 1.0, 1, 1.0, 2.0, InferenceTerminationReason.EOG)
                )
            } finally {
                concurrency.decrementAndGet()
            }
        }

        override fun close() {
            closeCount++
            events += "$name:close"
        }
    }

    private class BlockingEngine(
        private val generationStarted: CompletableDeferred<Unit>,
        private val generationRelease: CompletableDeferred<Unit>
    ) : RuntimeBackend {
        var cancelCount = 0

        override suspend fun initialize() = Unit

        override suspend fun generate(
            prompt: String,
            grammar: String?,
            enableThinking: Boolean
        ): InferenceResult {
            generationStarted.complete(Unit)
            generationRelease.await()
            return InferenceResult(
                text = prompt,
                metrics = InferenceMetrics(
                    0.0, 1, 1.0, 1.0, 1, 1.0, 2.0,
                    InferenceTerminationReason.EOG
                )
            )
        }

        override fun cancelGeneration(): Boolean {
            cancelCount++
            return true
        }

        override fun close() = Unit
    }
}
