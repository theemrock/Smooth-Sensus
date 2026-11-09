package com.theemrock.smooth.sensus.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.theemrock.smooth.bridge.ISmoothBridge
import com.theemrock.smooth.bridge.ISmoothCallback
import com.theemrock.smooth.bridge.SmoothBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real requests through the loaded model. Needs a GGUF already selected in the app. Never logs prompt or output. */
@RunWith(AndroidJUnit4::class)
class SmoothBridgeDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var bridge: ISmoothBridge
    private lateinit var connection: ServiceConnection

    private class Recorder : ISmoothCallback.Stub() {
        val events = LinkedBlockingQueue<List<String>>()
        @Volatile var chunks = 0

        override fun onChunk(requestId: String, text: String) { events.add(listOf("chunk", requestId)) }
        override fun onResult(requestId: String, json: String) { events.add(listOf("result", requestId, json)) }
        override fun onError(requestId: String, code: String, message: String) { events.add(listOf("error", requestId, code)) }

        /** Next terminal event (result or error); chunk events are counted and skipped. */
        fun terminal(timeoutSeconds: Long = 180): List<String> {
            while (true) {
                val event = events.poll(timeoutSeconds, TimeUnit.SECONDS) ?: error("no terminal event in ${timeoutSeconds}s")
                if (event[0] != "chunk") return event
                chunks++
            }
        }
    }

    @Before
    fun bind() {
        val bound = CountDownLatch(1)
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bridge = ISmoothBridge.Stub.asInterface(binder)
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, SmoothBridgeService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue(bound.await(10, TimeUnit.SECONDS))
    }

    @After
    fun unbind() = context.unbindService(connection)

    private val schema = """{"type":"object","properties":{"intent":{"type":"string","enum":["alarm","timer","note"]},"minutes":{"type":"integer"}},"required":["intent"],"additionalProperties":false}"""

    private fun structured(maxTokens: Int) =
        """{"capability":"llm.structured","command":"10 dakikalık bir zamanlayıcı kur","schema":$schema,"maxTokens":$maxTokens}"""

    @Test
    fun structuredReturnsSchemaConformingJson() {
        val recorder = Recorder()
        val id = bridge.request(structured(64), recorder)
        val event = recorder.terminal()
        assertEquals(listOf("result", id), event.take(2))

        val body = JsonParser.parseString(event[2]).asJsonObject
        assertTrue(body.getAsJsonObject("result").get("intent").asString in setOf("alarm", "timer", "note"))
        assertTrue(body.getAsJsonObject("metrics").has("queueWaitMs"))
    }

    @Test
    fun structuredCutAtTokenLimitIsTruncatedNotSuccess() {
        val recorder = Recorder()
        val id = bridge.request(structured(3), recorder)
        assertEquals(listOf("error", id, SmoothBridge.Error.TRUNCATED), recorder.terminal())
    }

    @Test
    fun unsupportedSchemaIsInvalidRequest() {
        val recorder = Recorder()
        val id = bridge.request("""{"capability":"llm.structured","command":"x","schema":{"${'$'}ref":"#/nope"}}""", recorder)
        assertEquals(listOf("error", id, SmoothBridge.Error.INVALID_REQUEST), recorder.terminal())
    }

    @Test
    fun generateStreamsChunksThenResult() {
        val recorder = Recorder()
        val id = bridge.request("""{"capability":"llm.generate","maxTokens":24,"messages":[{"role":"user","content":"Merhaba de."}]}""", recorder)
        val event = recorder.terminal()
        assertEquals(listOf("result", id), event.take(2))
        assertTrue("expected streamed chunks", recorder.chunks > 0)
    }

    @Test
    fun runningGenerationCanBeCancelled() {
        val recorder = Recorder()
        val id = bridge.request("""{"capability":"llm.generate","maxTokens":512,"messages":[{"role":"user","content":"Uzun bir hikaye anlat."}]}""", recorder)
        val first = recorder.events.poll(180, TimeUnit.SECONDS) ?: error("no output before cancel")
        assertEquals("chunk", first[0])

        assertTrue(bridge.cancel(id))
        assertEquals(listOf("error", id, SmoothBridge.Error.CANCELLED), recorder.terminal(30))
    }
}
