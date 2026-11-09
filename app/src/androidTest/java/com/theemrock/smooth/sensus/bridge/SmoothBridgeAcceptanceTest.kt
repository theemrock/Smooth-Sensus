package com.theemrock.smooth.sensus.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theemrock.smooth.bridge.ISmoothBridge
import com.theemrock.smooth.bridge.ISmoothCallback
import com.theemrock.smooth.sensus.SmoothApplication
import com.theemrock.smooth.sensus.inference.InferenceChatMessage
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Acceptance measurement: 20 fixed Turkish commands through the bridge, then bridge-behind-chat waiting.
 * Reports only counts, timings and field names (never prompt or model text). Measures, does not assert thresholds.
 * Must run in a fresh app process so the first request really is a cold start.
 */
@RunWith(AndroidJUnit4::class)
class SmoothBridgeAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val tr = Locale("tr", "TR")

    private class Recorder : ISmoothCallback.Stub() {
        val events = LinkedBlockingQueue<List<String>>()
        override fun onChunk(requestId: String, text: String) = Unit
        override fun onResult(requestId: String, json: String) { events.add(listOf("result", json)) }
        override fun onError(requestId: String, code: String, message: String) { events.add(listOf("error", code)) }
        fun terminal(): List<String> = events.poll(300, TimeUnit.SECONDS) ?: error("no answer in 300s")
    }

    private data class Run(val index: Int, val wallMs: Double, val error: String?, val body: JsonObject?)

    private fun percentile(sorted: List<Double>, p: Double) = sorted[ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1]

    private fun normalized(value: JsonElement?): String? = value?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase(tr)

    private fun normalizedTime(text: String?): String? {
        val match = text?.let { Regex("""^(\d{1,2})[:.](\d{2})$""").find(it) } ?: return text
        return "%02d:%s".format(match.groupValues[1].toInt(), match.groupValues[2])
    }

    private fun fieldMatches(key: String, expected: JsonElement, actual: JsonElement?): Boolean {
        if (actual == null || actual.isJsonNull) return false
        if (expected.isJsonObject) {
            val needle = expected.asJsonObject.get("contains").asString.lowercase(tr)
            return normalized(actual)?.contains(needle) == true
        }
        val want = expected.asJsonPrimitive
        return when {
            want.isBoolean -> actual.isJsonPrimitive && actual.asJsonPrimitive.isBoolean && actual.asBoolean == want.asBoolean
            want.isNumber -> actual.isJsonPrimitive && actual.asJsonPrimitive.isNumber && actual.asDouble == want.asDouble
            key == "time" -> normalizedTime(normalized(actual)) == normalizedTime(want.asString.lowercase(tr))
            else -> normalized(actual) == want.asString.lowercase(tr)
        }
    }

    @Test
    fun measure() {
        val fixture = JsonParser.parseString(
            instrumentation.context.assets.open("bridge/acceptance_fixture.json").bufferedReader().use { it.readText() }
        ).asJsonObject
        // Optional `-e schemas flat,perIntent` argument limits which fixture schemas run.
        val only = InstrumentationRegistry.getArguments().getString("schemas")?.split(',')?.toSet()
        val schemas = fixture.getAsJsonObject("schemas").entrySet()
            .filter { only == null || it.key in only }
            .map { it.key to it.value.toString() }
        val maxTokens = fixture.get("maxTokens").asInt
        val cases = fixture.getAsJsonArray("cases").map { it.asJsonObject }

        val bound = CountDownLatch(1)
        lateinit var bridge: ISmoothBridge
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { bridge = ISmoothBridge.Stub.asInterface(binder); bound.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, SmoothBridgeService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue(bound.await(10, TimeUnit.SECONDS))

        fun ask(index: Int, schema: String): Run {
            val command = cases[index].get("command").asString
            val request = JsonObject().apply {
                addProperty("capability", "llm.structured")
                addProperty("command", command)
                add("schema", JsonParser.parseString(schema))
                addProperty("maxTokens", maxTokens)
            }.toString()
            val recorder = Recorder()
            val start = System.nanoTime()
            bridge.request(request, recorder)
            val event = recorder.terminal()
            val wallMs = (System.nanoTime() - start) / 1e6
            return if (event[0] == "result") Run(index, wallMs, null, JsonParser.parseString(event[1]).asJsonObject)
            else Run(index, wallMs, event[1], null)
        }

        val report = StringBuilder()
        try {
            // Cold: the very first request after process start also loads the model.
            val cold = ask(0, schemas.first().second)
            report.appendLine("SOĞUK İLK İSTEK: ${"%.0f".format(cold.wallMs)} ms toplam, çıkarım ${cold.body?.getAsJsonObject("metrics")?.get("inferenceMs")?.asDouble?.let { "%.0f".format(it) }} ms")

            schemas.forEach { (schemaName, schema) ->
            report.appendLine("=== FORM: $schemaName ===")
            val runs = cases.indices.map { ask(it, schema) }
            val promptTokens = mutableListOf<Double>()
            val promptMs = mutableListOf<Double>()
            val generatedTokens = mutableListOf<Double>()
            var intentOk = 0
            var fieldsOk = 0
            var fieldsTotal = 0
            var truncated = 0
            var otherErrors = 0
            val tps = mutableListOf<Double>()
            runs.forEach { run ->
                val case = cases[run.index]
                when (run.error) {
                    "TRUNCATED" -> truncated++
                    null -> Unit
                    else -> otherErrors++
                }
                val result = run.body?.getAsJsonObject("result")
                val intentMatch = result?.get("intent")?.asString == case.get("intent").asString
                if (intentMatch) intentOk++
                val missed = mutableListOf<String>()
                case.getAsJsonObject("fields").entrySet().forEach { (key, expected) ->
                    fieldsTotal++
                    if (fieldMatches(key, expected, result?.get(key))) fieldsOk++ else missed += key
                }
                run.body?.getAsJsonObject("metrics")?.let { m ->
                    m.get("tokensPerSecond")?.asDouble?.let(tps::add)
                    m.get("promptTokens")?.asDouble?.let(promptTokens::add)
                    m.get("promptEvalMs")?.asDouble?.let(promptMs::add)
                    m.get("generatedTokens")?.asDouble?.let(generatedTokens::add)
                }
                report.appendLine(
                    "  #${run.index + 1}: tür ${if (intentMatch) "doğru" else "YANLIŞ"}, " +
                        "bilgi ${if (missed.isEmpty()) "tam" else "eksik: " + missed.joinToString()}, " +
                        "${"%.0f".format(run.wallMs)} ms" + (run.error?.let { ", hata $it" } ?: "")
                )
            }
            val latencies = runs.map { it.wallMs }.sorted()
            report.appendLine("TÜR DOĞRULUĞU: $intentOk/${cases.size}")
            report.appendLine("BİLGİ DOĞRULUĞU: $fieldsOk/$fieldsTotal")
            report.appendLine("TRUNCATED: $truncated, diğer hata: $otherErrors")
            report.appendLine(
                "GECİKME (sıcak, ${runs.size} istek): p50 ${"%.0f".format(percentile(latencies, 0.5))} ms, " +
                    "p95 ${"%.0f".format(percentile(latencies, 0.95))} ms, max ${"%.0f".format(latencies.last())} ms"
            )
            if (tps.isNotEmpty()) report.appendLine("ÜRETİM HIZI: ortalama ${"%.1f".format(tps.average())} token/sn")
            if (promptTokens.isNotEmpty()) report.appendLine(
                "İSTEM: ortalama ${"%.0f".format(promptTokens.average())} token, okuması ortalama ${"%.0f".format(promptMs.average())} ms; " +
                    "cevap ortalama ${"%.0f".format(generatedTokens.average())} token"
            )
            }

            // Bridge request arriving while a chat generation is running.
            val app = context.applicationContext as SmoothApplication
            repeat(3) { round ->
                val chatRunning = CountDownLatch(1)
                var chatFinishedAt = 0L
                val chat = thread {
                    runBlocking {
                        app.localModelRuntime.generateChatStreaming(
                            listOf(InferenceChatMessage("user", "Uzun bir hikaye anlat.")), false, 128
                        ) { chatRunning.countDown() }
                    }
                    chatFinishedAt = System.nanoTime()
                }
                assertTrue(chatRunning.await(120, TimeUnit.SECONDS))
                val sentAt = System.nanoTime()
                val run = ask(3, schemas.first().second)
                chat.join()
                val wait = run.body?.getAsJsonObject("metrics")?.get("queueWaitMs")?.asDouble
                report.appendLine(
                    "CHAT ARKASINDA #${round + 1}: bridge isteği ${"%.0f".format(run.wallMs)} ms sürdü, " +
                        "bunun ${wait?.let { "%.0f".format(it) }} ms'i bekleme; chat bridge isteğinden " +
                        "${"%.0f".format((chatFinishedAt - sentAt) / 1e6)} ms sonra bitti"
                )
            }
        } finally {
            context.unbindService(connection)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n" + report) })
        }
    }
}
