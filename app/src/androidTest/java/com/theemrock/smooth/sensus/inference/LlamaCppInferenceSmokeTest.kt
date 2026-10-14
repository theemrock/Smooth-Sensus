package com.theemrock.smooth.sensus.inference

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaCppInferenceSmokeTest {
    @Test
    fun realModelCancellationStopsGenerationAndRuntimeRecovers() = runBlocking {
        val modelPath = InstrumentationRegistry.getArguments().getString(MODEL_PATH_ARGUMENT)
        assumeTrue("Provide -e $MODEL_PATH_ARGUMENT /absolute/path/model.gguf", !modelPath.isNullOrBlank())
        assumeTrue("Configured model path must exist on device", File(modelPath!!).isFile)

        LlamaCppInferenceEngine(
            LlamaCppConfig(
                modelPath = modelPath,
                contextSize = 4096,
                threadCount = 4,
                maxTokens = 256,
                temperature = 0.0f,
                seed = 0
            )
        ).use { engine ->
            engine.initialize()
            val callbackChunks = Collections.synchronizedList(mutableListOf<String>())
            val callbackTimesMs = Collections.synchronizedList(mutableListOf<Long>())
            val firstCallbackSignal = CompletableDeferred<Unit>()
            val firstCallbackTime = AtomicLong(-1L)
            val inferenceStartMs = SystemClock.elapsedRealtime()
            val generation = async(Dispatchers.Default) {
                engine.generateChatStreaming(
                    messages = listOf(
                        InferenceChatMessage(
                            role = "user",
                            content = "List the integers from 1 through 200 in order, separated by commas. " +
                                "Do not summarize or stop early."
                        )
                    ),
                    enableThinking = false,
                    maxTokens = 256
                ) { chunk ->
                    if (chunk.isNotEmpty()) {
                        val callbackTime = SystemClock.elapsedRealtime()
                        callbackChunks += chunk
                        callbackTimesMs += callbackTime
                        if (firstCallbackTime.compareAndSet(-1L, callbackTime)) {
                            firstCallbackSignal.complete(Unit)
                        }
                    }
                }
            }

            withTimeout(60_000) { firstCallbackSignal.await() }
            val firstCallbackMs = firstCallbackTime.get()
            var cancelRequestMs: Long
            do {
                cancelRequestMs = SystemClock.elapsedRealtime()
            } while (cancelRequestMs <= firstCallbackMs)
            val callbackCountBeforeCancel = callbackChunks.size
            val cancelAccepted = engine.cancelGeneration()
            val result = withTimeout(60_000) { generation.await() }
            val inferenceReturnMs = SystemClock.elapsedRealtime()
            val chunks = callbackChunks.toList()
            val callbackTimes = callbackTimesMs.toList()
            val concatenatedText = chunks.joinToString(separator = "")

            val recoveryResult = engine.generateChat(
                messages = listOf(InferenceChatMessage("user", "Reply with exactly: RECOVERED")),
                enableThinking = false,
                maxTokens = 12
            )
            val noActiveCancelAccepted = engine.cancelGeneration()
            val postNoActiveResult = engine.generateChat(
                messages = listOf(InferenceChatMessage("user", "Reply with exactly: READY")),
                enableThinking = false,
                maxTokens = 12
            )

            val metrics = result.metrics
            val callbackReport = chunks.indices.joinToString(prefix = "[", postfix = "]") { index ->
                "${callbackTimes[index] - inferenceStartMs}:${escapeForLog(chunks[index])}"
            }
            Log.i(
                CANCELLATION_SMOKE_TAG,
                "P05DC_CANCELLATION_SMOKE" +
                    "|modelLoadDurationMs=${metrics.modelLoadDurationMs}" +
                    "|promptTokenCount=${metrics.promptTokenCount}" +
                    "|promptEvalDurationMs=${metrics.promptEvalDurationMs}" +
                    "|timeToFirstTokenMs=${metrics.timeToFirstTokenMs}" +
                    "|generatedTokenCount=${metrics.generatedTokenCount}" +
                    "|generationDurationMs=${metrics.generationDurationMs}" +
                    "|totalInferenceDurationMs=${metrics.totalInferenceDurationMs}" +
                    "|contextUsed=${metrics.contextUsed}" +
                    "|contextMax=${metrics.contextMax}" +
                    "|contextUsageRatio=${metrics.contextUsageRatio}" +
                    "|inferenceStartMs=$inferenceStartMs" +
                    "|firstCallbackMs=$firstCallbackMs" +
                    "|cancelRequestMs=$cancelRequestMs" +
                    "|inferenceReturnMs=$inferenceReturnMs" +
                    "|callbackCountBeforeCancel=$callbackCountBeforeCancel" +
                    "|totalCallbackCount=${chunks.size}" +
                    "|cancelAccepted=$cancelAccepted" +
                    "|terminationReason=${metrics.terminationReason}" +
                    "|callbacks=$callbackReport" +
                    "|concatenatedText=${escapeForLog(concatenatedText)}" +
                    "|finalText=${escapeForLog(result.text)}" +
                    "|concatenationMatches=${concatenatedText == result.text}" +
                    "|recoveryText=${escapeForLog(recoveryResult.text)}" +
                    "|recoveryTermination=${recoveryResult.metrics.terminationReason}" +
                    "|noActiveCancelAccepted=$noActiveCancelAccepted" +
                    "|postNoActiveText=${escapeForLog(postNoActiveResult.text)}" +
                    "|postNoActiveTermination=${postNoActiveResult.metrics.terminationReason}" +
                    "|P05DC_CANCELLATION_SMOKE_END"
            )

            assertTrue("At least one callback must precede cancellation", callbackCountBeforeCancel >= 1)
            assertTrue("Cancel request must follow the first callback", cancelRequestMs > firstCallbackMs)
            assertTrue("Active native generation must accept cancellation", cancelAccepted)
            assertTrue("Inference must return after cancellation", inferenceReturnMs >= cancelRequestMs)
            assertEquals(InferenceTerminationReason.CANCELLED, metrics.terminationReason)
            assertTrue("Cancelled generation must retain generated tokens", metrics.generatedTokenCount > 0)
            assertEquals("Cancelled chunks must equal final text", result.text, concatenatedText)
            assertTrue("Cancelled final text must be non-empty", result.text.isNotEmpty())
            assertFalse("Cancelled text must not contain replacement characters", result.text.contains('\uFFFD'))
            assertTrue("Cancelled final text must be valid UTF-8", Charsets.UTF_8.newEncoder().canEncode(result.text))
            assertTrue("Every cancelled chunk must be valid UTF-8", chunks.all {
                it.isNotEmpty() && !it.contains('\uFFFD') && Charsets.UTF_8.newEncoder().canEncode(it)
            })
            assertTrue("Context maximum must be positive", metrics.contextMax > 0)
            assertTrue("Context usage must be positive", metrics.contextUsed > 0)
            assertTrue("Context usage must not exceed maximum", metrics.contextUsed <= metrics.contextMax)
            assertTrue("Prompt token count must be positive", metrics.promptTokenCount > 0)
            assertTrue("Recovery inference must be non-empty", recoveryResult.text.isNotEmpty())
            assertTrue(
                "Recovery inference must not inherit stale cancellation",
                recoveryResult.metrics.terminationReason != InferenceTerminationReason.CANCELLED
            )
            assertFalse("Cancellation with no active generation must return false", noActiveCancelAccepted)
            assertTrue("Inference after no-active cancellation must be non-empty", postNoActiveResult.text.isNotEmpty())
            assertTrue(
                "No-active cancellation must not poison the next generation",
                postNoActiveResult.metrics.terminationReason != InferenceTerminationReason.CANCELLED
            )
        }
    }

    @Test
    fun realModelStreamsIncrementallyAndRuntimeRecovers() = runBlocking {
        val modelPath = InstrumentationRegistry.getArguments().getString(MODEL_PATH_ARGUMENT)
        assumeTrue("Provide -e $MODEL_PATH_ARGUMENT /absolute/path/model.gguf", !modelPath.isNullOrBlank())
        assumeTrue("Configured model path must exist on device", File(modelPath!!).isFile)

        LlamaCppInferenceEngine(
            LlamaCppConfig(
                modelPath = modelPath,
                contextSize = 4096,
                threadCount = 4,
                maxTokens = 48,
                temperature = 0.0f,
                seed = 0
            )
        ).use { engine ->
            engine.initialize()

            val callbackChunks = mutableListOf<String>()
            val callbackTimesMs = mutableListOf<Long>()
            val inferenceStartMs = SystemClock.elapsedRealtime()
            val result = engine.generateChatStreaming(
                messages = listOf(
                    InferenceChatMessage(
                        role = "user",
                        content = "Reply with exactly these eight words and no extra text: " +
                            "Native streaming callbacks arrive before inference returns today."
                    )
                ),
                enableThinking = false,
                maxTokens = 48
            ) { chunk ->
                callbackChunks += chunk
                callbackTimesMs += SystemClock.elapsedRealtime()
            }
            val inferenceReturnMs = SystemClock.elapsedRealtime()
            val concatenatedText = callbackChunks.joinToString(separator = "")

            val recoveryResult = engine.generateChat(
                messages = listOf(
                    InferenceChatMessage(role = "user", content = "Reply with exactly: OK")
                ),
                enableThinking = false,
                maxTokens = 8
            )

            val metrics = result.metrics
            val firstCallbackMs = callbackTimesMs.firstOrNull() ?: -1L
            val lastCallbackMs = callbackTimesMs.lastOrNull() ?: -1L
            val callbackReport = callbackChunks.indices.joinToString(prefix = "[", postfix = "]") { index ->
                "${callbackTimesMs[index] - inferenceStartMs}:${escapeForLog(callbackChunks[index])}"
            }
            Log.i(
                STREAMING_SMOKE_TAG,
                "P05DB_STREAMING_SMOKE" +
                    "|modelLoadDurationMs=${metrics.modelLoadDurationMs}" +
                    "|promptTokenCount=${metrics.promptTokenCount}" +
                    "|promptEvalDurationMs=${metrics.promptEvalDurationMs}" +
                    "|timeToFirstTokenMs=${metrics.timeToFirstTokenMs}" +
                    "|generatedTokenCount=${metrics.generatedTokenCount}" +
                    "|generationDurationMs=${metrics.generationDurationMs}" +
                    "|totalInferenceDurationMs=${metrics.totalInferenceDurationMs}" +
                    "|contextUsed=${metrics.contextUsed}" +
                    "|contextMax=${metrics.contextMax}" +
                    "|contextUsageRatio=${metrics.contextUsageRatio}" +
                    "|terminationReason=${metrics.terminationReason}" +
                    "|callbackCount=${callbackChunks.size}" +
                    "|inferenceStartMs=$inferenceStartMs" +
                    "|firstCallbackMs=$firstCallbackMs" +
                    "|lastCallbackMs=$lastCallbackMs" +
                    "|inferenceReturnMs=$inferenceReturnMs" +
                    "|callbacks=$callbackReport" +
                    "|concatenatedText=${escapeForLog(concatenatedText)}" +
                    "|finalText=${escapeForLog(result.text)}" +
                    "|concatenationMatches=${concatenatedText == result.text}" +
                    "|recoveryText=${escapeForLog(recoveryResult.text)}" +
                    "|P05DB_STREAMING_SMOKE_END"
            )

            assertTrue("Expected at least two streaming callbacks", callbackChunks.size >= 2)
            assertTrue("Every callback chunk must be non-empty", callbackChunks.all { it.isNotEmpty() })
            assertTrue(
                "Callback timestamps must preserve callback order",
                callbackTimesMs.zipWithNext().all { (previous, next) -> previous <= next }
            )
            assertTrue("First callback must occur before inference returns", firstCallbackMs < inferenceReturnMs)
            assertTrue("Last callback must not occur after inference returns", lastCallbackMs <= inferenceReturnMs)
            assertEquals("Callback concatenation must equal final text", result.text, concatenatedText)
            assertTrue("Final streaming result must be non-empty", result.text.isNotEmpty())
            assertFalse("Final text must not contain a replacement character", result.text.contains('\uFFFD'))
            assertTrue("Final text must be valid UTF-8", Charsets.UTF_8.newEncoder().canEncode(result.text))
            assertTrue(
                "Every callback must be valid UTF-8",
                callbackChunks.all { !it.contains('\uFFFD') && Charsets.UTF_8.newEncoder().canEncode(it) }
            )
            assertTrue("Generated token count must be positive", metrics.generatedTokenCount > 0)
            assertTrue("Context maximum must be positive", metrics.contextMax > 0)
            assertTrue("Context usage must be positive", metrics.contextUsed > 0)
            assertTrue("Context usage must not exceed maximum", metrics.contextUsed <= metrics.contextMax)
            assertTrue("Prompt token count must be positive", metrics.promptTokenCount > 0)
            assertTrue(
                "Termination reason must represent a completed generation",
                metrics.terminationReason in setOf(
                    InferenceTerminationReason.EOG,
                    InferenceTerminationReason.MAX_TOKENS,
                    InferenceTerminationReason.CONTEXT_FULL
                )
            )
            assertTrue("Model load duration must be positive", metrics.modelLoadDurationMs > 0.0)
            assertTrue("Prompt evaluation duration must be non-negative", metrics.promptEvalDurationMs >= 0.0)
            assertTrue("Time to first token must be non-negative", metrics.timeToFirstTokenMs >= 0.0)
            assertTrue("Generation duration must be positive", metrics.generationDurationMs > 0.0)
            assertTrue("Total inference duration must be positive", metrics.totalInferenceDurationMs > 0.0)
            assertTrue(
                "Total duration must include prompt evaluation",
                metrics.totalInferenceDurationMs >= metrics.promptEvalDurationMs
            )
            assertTrue(
                "Total duration must include generation",
                metrics.totalInferenceDurationMs >= metrics.generationDurationMs
            )
            assertTrue(
                "Time to first token must not exceed total duration",
                metrics.timeToFirstTokenMs <= metrics.totalInferenceDurationMs
            )
            assertEquals(
                "Context usage ratio must match context counts",
                metrics.contextUsed.toDouble() / metrics.contextMax,
                metrics.contextUsageRatio,
                0.0
            )
            assertTrue("Same loaded runtime must complete a second inference", recoveryResult.text.isNotEmpty())
        }
    }

    @Test
    fun controlledRuntimeExperiment() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val modelPath = arguments.getString(MODEL_PATH_ARGUMENT)
        assumeTrue("Provide -e $MODEL_PATH_ARGUMENT /absolute/path/model.gguf", !modelPath.isNullOrBlank())
        assumeTrue("Configured model path must exist on device", File(modelPath!!).isFile)
        val useJinja = arguments.getString("useJinja")?.toBooleanStrictOrNull() ?: true
        val enableThinking = arguments.getString("enableThinking")?.toBooleanStrictOrNull() ?: false
        val maxTokens = arguments.getString("maxTokens")?.toIntOrNull() ?: 64
        LlamaCppInferenceEngine(
            LlamaCppConfig(
                modelPath = modelPath,
                contextSize = 4096,
                threadCount = 4,
                maxTokens = maxTokens,
                temperature = 0.0f,
                seed = 0,
                useJinjaTemplate = useJinja
            )
        ).use { engine ->
            engine.initialize()
            val result = engine.generate("Reply with one short word.", enableThinking = enableThinking)
            val metrics = result.metrics
            Log.i(
                "SMOOTH_EXPERIMENT",
                "RESULT|jinja=$useJinja|thinking=$enableThinking|loadMs=${metrics.modelLoadDurationMs}" +
                    "|promptTokens=${metrics.promptTokenCount}|promptMs=${metrics.promptEvalDurationMs}" +
                    "|promptTps=${metrics.promptTokensPerSecond}|ttftMs=${metrics.timeToFirstTokenMs}" +
                    "|generatedTokens=${metrics.generatedTokenCount}|generationMs=${metrics.generationDurationMs}" +
                    "|generationTps=${metrics.generationTokensPerSecond}|totalMs=${metrics.totalInferenceDurationMs}" +
                    "|context=${metrics.contextUsed}/${metrics.contextMax}|contextPct=${metrics.contextUsageRatio * 100.0}" +
                    "|termination=${metrics.terminationReason}|output=${result.text.replace("\n", "\\n")}" 
            )
            assertTrue(result.text.isNotBlank())
        }
    }

    @Test
    fun realModelGeneratesNonEmptyText() = runBlocking {
        withEngine { engine ->
            assertTrue(engine.generate("Reply with one short word.").text.isNotBlank())
        }
    }

    @Test
    fun realModelHonorsGenericGrammar() = runBlocking {
        withEngine { engine ->
            val grammar = """root ::= "{\"ok\":true}""""
            assertEquals("{\"ok\":true}", engine.generate("Return an acknowledgement.", grammar).text)
        }
    }

    private suspend fun withEngine(block: suspend (LlamaCppInferenceEngine) -> Unit) {
        val modelPath = InstrumentationRegistry.getArguments().getString(MODEL_PATH_ARGUMENT)
        assumeTrue("Provide -e $MODEL_PATH_ARGUMENT /absolute/path/model.gguf", !modelPath.isNullOrBlank())
        assumeTrue("Configured model path must exist on device", File(modelPath!!).isFile)

        LlamaCppInferenceEngine(
            LlamaCppConfig(
                modelPath = modelPath,
                contextSize = 2048,
                threadCount = 4,
                maxTokens = 32,
                temperature = 0.0f,
                seed = 0
            )
        ).use { engine ->
            engine.initialize()
            block(engine)
        }
    }

    private companion object {
        const val MODEL_PATH_ARGUMENT = "llamaModelPath"
        const val STREAMING_SMOKE_TAG = "P05DB_STREAMING_SMOKE"
        const val CANCELLATION_SMOKE_TAG = "P05DC_CANCELLATION_SMOKE"

        fun escapeForLog(value: String): String = value
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
    }
}
