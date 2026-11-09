package com.theemrock.smooth.sensus.bridge

import com.theemrock.smooth.bridge.SmoothBridge
import com.theemrock.smooth.sensus.inference.ActiveModel
import com.theemrock.smooth.sensus.inference.GenerationTag
import com.theemrock.smooth.sensus.inference.InferenceChatMessage
import com.theemrock.smooth.sensus.inference.InferenceMetrics
import com.theemrock.smooth.sensus.inference.InferenceResult
import com.theemrock.smooth.sensus.inference.InferenceTerminationReason
import com.theemrock.smooth.sensus.inference.InferenceTextChunkCallback
import com.theemrock.smooth.sensus.inference.LocalModelRuntimeBoundary
import com.theemrock.smooth.sensus.inference.StoredModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeExecutorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = Any()

    @After
    fun tearDown() = scope.cancel()

    private fun metrics(reason: InferenceTerminationReason) = InferenceMetrics(
        modelLoadDurationMs = 0.0, promptTokenCount = 10, promptEvalDurationMs = 5.0, timeToFirstTokenMs = 6.0,
        generatedTokenCount = 20, generationDurationMs = 100.0, totalInferenceDurationMs = 110.0, terminationReason = reason
    )

    private fun result(text: String, reason: InferenceTerminationReason = InferenceTerminationReason.EOG) =
        InferenceResult(text, metrics(reason))

    /** Fake runtime: each call takes its next scripted behaviour; blocks on [gate] when one is installed. */
    private class FakeRuntime : LocalModelRuntimeBoundary {
        val grammars = CopyOnWriteArrayList<String?>()
        val thinking = CopyOnWriteArrayList<Boolean>()
        val maxTokensSeen = CopyOnWriteArrayList<Int>()
        val tagsCancelled = CopyOnWriteArrayList<GenerationTag>()
        val calls = CopyOnWriteArrayList<GenerationTag?>()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var response: InferenceResult? = null
        @Volatile var chunks: List<String> = emptyList()

        override suspend fun switchTo(storedModel: StoredModel) = ActiveModel("m", 1)
        override suspend fun generate(prompt: String, grammar: String?, enableThinking: Boolean) = response!!
        override suspend fun unload() = Unit
        override fun activeModel(): ActiveModel? = ActiveModel("m", 1)
        override fun cancelGeneration(tag: GenerationTag): Boolean { tagsCancelled += tag; return true }

        override suspend fun generateWithMaxTokens(prompt: String, grammar: String?, enableThinking: Boolean, maxTokens: Int): InferenceResult {
            grammars += grammar; thinking += enableThinking; maxTokensSeen += maxTokens
            calls += currentCoroutineContext()[GenerationTag]
            gate?.await()
            return response!!
        }

        override suspend fun generateChatStreaming(
            messages: List<InferenceChatMessage>, enableThinking: Boolean, maxTokens: Int, onChunk: InferenceTextChunkCallback
        ): InferenceResult {
            calls += currentCoroutineContext()[GenerationTag]
            chunks.forEach(onChunk::onTextChunk)
            gate?.await()
            return response!!
        }
    }

    private class RecordingSink : BridgeSink {
        val events = LinkedBlockingQueue<List<String>>()
        override fun onChunk(requestId: String, text: String) { events.add(listOf("chunk", requestId, text)) }
        override fun onResult(requestId: String, json: String) { events.add(listOf("result", requestId, json)) }
        override fun onError(requestId: String, code: String, message: String) { events.add(listOf("error", requestId, code)) }
        fun next(): List<String> = events.poll(5, TimeUnit.SECONDS) ?: error("no event within 5s")
        fun noMoreEvents() = assertNull(events.poll(200, TimeUnit.MILLISECONDS))
    }

    private fun executor(
        runtime: FakeRuntime,
        ensureModel: suspend () -> Boolean = { true },
        maxPending: Int = 8,
        grammar: (String) -> String = { "root ::= object" }
    ) = BridgeExecutor(runtime, scope, ensureModel, grammar, newId = counter(), maxPending = maxPending)

    private fun counter(): () -> String { var n = 0; return { "r${++n}" } }

    private val structured = """{"capability":"llm.structured","command":"7ye alarm kur","schema":{"type":"object"},"maxTokens":64}"""
    private val generate = """{"capability":"llm.generate","messages":[{"role":"user","content":"selam"}]}"""

    @Test
    fun `structured request uses grammar, THINK OFF and the requested token cap`() {
        val runtime = FakeRuntime().apply { response = result("""{"intent":"alarm"}""") }
        val sink = RecordingSink()
        val id = executor(runtime).submit(structured, sink, client)

        val event = sink.next()
        assertEquals(listOf("result", id), event.take(2))
        assertTrue(event[2].contains(""""intent":"alarm""""))
        assertTrue(event[2].contains(""""queueWaitMs""""))
        assertEquals(listOf<String?>("root ::= object"), runtime.grammars)
        assertEquals(listOf(false), runtime.thinking)
        assertEquals(listOf(64), runtime.maxTokensSeen)
    }

    @Test
    fun `output cut at the token limit is TRUNCATED and never a result`() {
        val runtime = FakeRuntime().apply { response = result("""{"intent":"al""", InferenceTerminationReason.MAX_TOKENS) }
        val sink = RecordingSink()
        executor(runtime).submit(structured, sink, client)

        assertEquals(listOf("error", "r1", SmoothBridge.Error.TRUNCATED), sink.next())
        sink.noMoreEvents()
    }

    @Test
    fun `complete but invalid JSON output is INTERNAL`() {
        val runtime = FakeRuntime().apply { response = result("""{"intent":""") }
        val sink = RecordingSink()
        executor(runtime).submit(structured, sink, client)
        assertEquals(listOf("error", "r1", SmoothBridge.Error.INTERNAL), sink.next())
    }

    @Test
    fun `unsupported schema is INVALID_REQUEST`() {
        val runtime = FakeRuntime().apply { response = result("{}") }
        val sink = RecordingSink()
        executor(runtime, grammar = { throw IllegalArgumentException("bad schema") }).submit(structured, sink, client)
        assertEquals(listOf("error", "r1", SmoothBridge.Error.INVALID_REQUEST), sink.next())
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `missing model is MODEL_NOT_LOADED`() {
        val runtime = FakeRuntime().apply { response = result("{}") }
        val sink = RecordingSink()
        executor(runtime, ensureModel = { false }).submit(structured, sink, client)
        assertEquals(listOf("error", "r1", SmoothBridge.Error.MODEL_NOT_LOADED), sink.next())
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `invalid request is reported through the callback`() {
        val sink = RecordingSink()
        val id = executor(FakeRuntime()).submit("nope", sink, client)
        assertEquals(listOf("error", id, SmoothBridge.Error.INVALID_REQUEST), sink.next())
    }

    @Test
    fun `generate streams chunks then returns the text`() {
        val runtime = FakeRuntime().apply { response = result("merhaba dünya"); chunks = listOf("merhaba", " dünya") }
        val sink = RecordingSink()
        executor(runtime).submit(generate, sink, client)

        assertEquals(listOf("chunk", "r1", "merhaba"), sink.next())
        assertEquals(listOf("chunk", "r1", " dünya"), sink.next())
        val done = sink.next()
        assertEquals("result", done[0])
        assertTrue(done[2].contains("merhaba dünya"))
    }

    @Test
    fun `requests run strictly one at a time in order`() {
        val runtime = FakeRuntime().apply { response = result("{}"); gate = CompletableDeferred() }
        val sink = RecordingSink()
        val exec = executor(runtime)
        exec.submit(structured, sink, client)
        exec.submit(structured, sink, client)
        Thread.sleep(300)
        assertEquals(1, runtime.calls.size)

        runtime.gate!!.complete(Unit)
        assertEquals("r1", sink.next()[1])
        assertEquals("r2", sink.next()[1])
        assertEquals(2, runtime.calls.size)
    }

    @Test
    fun `queued request is cancelled immediately without reaching the runtime or native cancel`() {
        val runtime = FakeRuntime().apply { response = result("{}"); gate = CompletableDeferred() }
        val sink = RecordingSink()
        val exec = executor(runtime)
        exec.submit(structured, sink, client)
        val queued = exec.submit(structured, sink, client)
        Thread.sleep(300)

        assertTrue(exec.cancel(queued))
        assertEquals(listOf("error", queued, SmoothBridge.Error.CANCELLED), sink.next())
        assertTrue(runtime.tagsCancelled.isEmpty())

        runtime.gate!!.complete(Unit)
        assertEquals("r1", sink.next()[1])
        sink.noMoreEvents()
        assertEquals(1, runtime.calls.size)
    }

    @Test
    fun `running request is cancelled through the native cancel with its own tag`() {
        val runtime = FakeRuntime().apply { response = result("{}"); gate = CompletableDeferred() }
        val sink = RecordingSink()
        val exec = executor(runtime)
        val id = exec.submit(structured, sink, client)
        Thread.sleep(300)

        assertTrue(exec.cancel(id))
        assertEquals(listOf("error", id, SmoothBridge.Error.CANCELLED), sink.next())
        assertEquals(runtime.calls.single(), runtime.tagsCancelled.single())
        assertFalse(exec.cancel(id))
    }

    @Test
    fun `native CANCELLED termination maps to CANCELLED`() {
        val runtime = FakeRuntime().apply { response = result("", InferenceTerminationReason.CANCELLED) }
        val sink = RecordingSink()
        executor(runtime).submit(structured, sink, client)
        assertEquals(listOf("error", "r1", SmoothBridge.Error.CANCELLED), sink.next())
    }

    @Test
    fun `queue overflow is BUSY`() {
        val runtime = FakeRuntime().apply { response = result("{}"); gate = CompletableDeferred() }
        val sink = RecordingSink()
        val exec = executor(runtime, maxPending = 1)
        exec.submit(structured, sink, client)
        val rejected = exec.submit(structured, sink, client)
        assertEquals(listOf("error", rejected, SmoothBridge.Error.BUSY), sink.next())
    }

    @Test
    fun `dead client cancels all of its requests and no others`() {
        val runtime = FakeRuntime().apply { response = result("{}"); gate = CompletableDeferred() }
        val dying = RecordingSink()
        val other = RecordingSink()
        val otherClient = Any()
        val exec = executor(runtime)
        exec.submit(structured, dying, client)
        exec.submit(structured, dying, client)
        val survivor = exec.submit(structured, other, otherClient)
        Thread.sleep(300)

        exec.cancelClient(client)
        assertEquals(SmoothBridge.Error.CANCELLED, dying.next()[2])
        assertEquals(SmoothBridge.Error.CANCELLED, dying.next()[2])

        runtime.gate!!.complete(Unit)
        assertEquals(listOf("result", survivor), other.next().take(2))
    }

    @Test
    fun `describe reports bridgeVersion and capabilities`() {
        val json = executor(FakeRuntime()).describe()
        assertTrue(json.contains(""""bridgeVersion":${SmoothBridge.BRIDGE_VERSION}"""))
        assertTrue(json.contains(SmoothBridge.CAPABILITY_STRUCTURED) && json.contains(SmoothBridge.CAPABILITY_GENERATE))
        assertNotNull(parseStrictJson(json))
    }
}
