package com.theemrock.smooth.sensus.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.theemrock.smooth.bridge.SmoothBridge
import com.theemrock.smooth.sensus.inference.GenerationTag
import com.theemrock.smooth.sensus.inference.InferenceMetrics
import com.theemrock.smooth.sensus.inference.InferenceResult
import com.theemrock.smooth.sensus.inference.InferenceTerminationReason
import com.theemrock.smooth.sensus.inference.LocalModelRuntimeBoundary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Receives the outcome of one request; exactly one of onResult/onError is called, after any chunks. */
interface BridgeSink {
    fun onChunk(requestId: String, text: String)
    fun onResult(requestId: String, json: String)
    fun onError(requestId: String, code: String, message: String)
}

/**
 * Android-free core of the bridge: validates requests, runs them one at a time through the shared runtime,
 * supports cancellation of queued and running work. Prompt and output text are never logged.
 */
class BridgeExecutor(
    private val runtime: LocalModelRuntimeBoundary,
    scope: CoroutineScope,
    private val ensureModel: suspend () -> Boolean,
    private val schemaToGrammar: (String) -> String,
    private val modelLoaded: () -> Boolean = { runtime.activeModel() != null },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowNanos: () -> Long = System::nanoTime,
    private val maxPending: Int = 8
) {
    private class Entry(val id: String, val request: BridgeRequest, val sink: BridgeSink, val client: Any) {
        val tag = GenerationTag()
        @Volatile var job: Job? = null
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val queue = Channel<Entry>(Channel.UNLIMITED)
    private val worker = scope.launch { for (entry in queue) runEntry(entry) }

    fun describe(): String = JsonObject().apply {
        addProperty("bridgeVersion", SmoothBridge.BRIDGE_VERSION)
        add("capabilities", JsonArray().apply {
            add(SmoothBridge.CAPABILITY_STRUCTURED)
            add(SmoothBridge.CAPABILITY_GENERATE)
        })
        addProperty("modelLoaded", modelLoaded())
        addProperty("maxPending", maxPending)
    }.toString()

    /** Returns the requestId; validation and capacity failures are reported through [sink] like any other error. */
    fun submit(json: String, sink: BridgeSink, client: Any): String {
        val id = newId()
        val request = try {
            parseBridgeRequest(json)
        } catch (failure: BridgeRequestException) {
            sink.onError(id, failure.code, failure.message.orEmpty())
            return id
        }
        // shortcut: size check and insert are not atomic, so concurrent binder threads may overshoot by a few; fine for a soft cap.
        if (entries.size >= maxPending) {
            sink.onError(id, SmoothBridge.Error.BUSY, "Request queue is full")
            return id
        }
        entries[id] = Entry(id, request, sink, client)
        queue.trySend(entries.getValue(id))
        return id
    }

    fun cancel(requestId: String): Boolean {
        val entry = entries[requestId] ?: return false
        cancelEntry(entry)
        return true
    }

    fun cancelClient(client: Any) = entries.values.filter { it.client === client }.forEach(::cancelEntry)

    fun shutdown() {
        entries.values.toList().forEach(::cancelEntry)
        queue.close()
        worker.cancel()
    }

    private fun cancelEntry(entry: Entry) {
        entry.cancelled.set(true)
        val job = entry.job
        if (job == null) {
            // Still queued: finish now instead of waiting behind the running request.
            fail(entry, SmoothBridge.Error.CANCELLED, "Request cancelled")
            return
        }
        job.cancel()
        runtime.cancelGeneration(entry.tag)
    }

    private suspend fun runEntry(entry: Entry) = coroutineScope {
        if (entry.finished.get()) return@coroutineScope
        val job = launch(entry.tag, start = CoroutineStart.LAZY) { execute(entry) }
        entry.job = job
        if (entry.cancelled.get()) job.cancel() else job.start()
        job.join()
        fail(entry, SmoothBridge.Error.CANCELLED, "Request cancelled") // no-op when already finished
    }

    private suspend fun execute(entry: Entry) {
        try {
            if (entry.finished.get()) return
            if (!ensureModel()) return fail(entry, SmoothBridge.Error.MODEL_NOT_LOADED, "No model is loaded")
            val startedAt = nowNanos()
            val result = when (val request = entry.request) {
                is BridgeRequest.Structured -> {
                    val grammar = try {
                        schemaToGrammar(request.schema.toString())
                    } catch (_: Exception) {
                        return fail(entry, SmoothBridge.Error.INVALID_REQUEST, "Schema is not supported")
                    }
                    runtime.generateWithMaxTokens(structuredPrompt(request), grammar, false, request.maxTokens)
                }
                is BridgeRequest.Generate -> runtime.generateChatStreaming(request.messages, false, request.maxTokens) { chunk ->
                    if (!entry.finished.get()) entry.sink.onChunk(entry.id, chunk)
                }
            }
            complete(entry, result, (nowNanos() - startedAt) / 1_000_000.0)
        } catch (_: CancellationException) {
            fail(entry, SmoothBridge.Error.CANCELLED, "Request cancelled")
        } catch (_: Exception) {
            fail(entry, SmoothBridge.Error.INTERNAL, "Internal error")
        }
    }

    private fun complete(entry: Entry, result: InferenceResult, wallMs: Double) {
        val metrics = result.metrics
        val structured = entry.request as? BridgeRequest.Structured
        when (metrics.terminationReason) {
            InferenceTerminationReason.CANCELLED -> fail(entry, SmoothBridge.Error.CANCELLED, "Request cancelled")
            InferenceTerminationReason.ERROR -> fail(entry, SmoothBridge.Error.INTERNAL, "Generation failed")
            InferenceTerminationReason.MAX_TOKENS, InferenceTerminationReason.CONTEXT_FULL ->
                if (structured != null) {
                    fail(entry, SmoothBridge.Error.TRUNCATED, "Output reached the token limit")
                } else {
                    succeed(entry, JsonObject().apply { addProperty("text", result.text) }, metrics, wallMs)
                }
            InferenceTerminationReason.EOG -> if (structured != null) {
                val parsed = parseStrictJson(result.text)
                if (parsed == null) {
                    fail(entry, SmoothBridge.Error.INTERNAL, "Output is not valid JSON")
                } else {
                    succeed(entry, JsonObject().apply { add("result", parsed) }, metrics, wallMs)
                }
            } else {
                succeed(entry, JsonObject().apply { addProperty("text", result.text) }, metrics, wallMs)
            }
        }
    }

    private fun succeed(entry: Entry, body: JsonObject, metrics: InferenceMetrics, wallMs: Double) {
        body.addProperty("capability", if (entry.request is BridgeRequest.Structured) SmoothBridge.CAPABILITY_STRUCTURED else SmoothBridge.CAPABILITY_GENERATE)
        body.addProperty("finishReason", metrics.terminationReason.name)
        body.add("metrics", JsonObject().apply {
            addProperty("promptTokens", metrics.promptTokenCount)
            addProperty("generatedTokens", metrics.generatedTokenCount)
            addProperty("tokensPerSecond", metrics.generationTokensPerSecond)
            addProperty("inferenceMs", metrics.totalInferenceDurationMs)
            // Wall time of the runtime call minus pure inference time: how long it waited behind other work (e.g. chat).
            addProperty("queueWaitMs", (wallMs - metrics.totalInferenceDurationMs).coerceAtLeast(0.0))
        })
        finish(entry) { entry.sink.onResult(entry.id, body.toString()) }
    }

    private fun fail(entry: Entry, code: String, message: String) =
        finish(entry) { entry.sink.onError(entry.id, code, message) }

    private fun finish(entry: Entry, deliver: () -> Unit) {
        if (!entry.finished.compareAndSet(false, true)) return
        entries.remove(entry.id)
        runCatching(deliver) // a dead client must not break the worker
    }

    private fun structuredPrompt(request: BridgeRequest.Structured) =
        "Aşağıdaki Türkçe komutu, verilen JSON Schema'ya uyan tek bir JSON değerine dönüştür. Yalnızca JSON üret.\n" +
            "Şema: ${request.schema}\nKomut: ${request.command}"
}
