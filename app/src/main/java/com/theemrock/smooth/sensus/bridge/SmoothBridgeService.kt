package com.theemrock.smooth.sensus.bridge

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import com.theemrock.smooth.bridge.ISmoothBridge
import com.theemrock.smooth.bridge.ISmoothCallback
import com.theemrock.smooth.sensus.SmoothApplication
import com.theemrock.smooth.sensus.inference.ModelStorage
import com.theemrock.smooth.sensus.inference.SelectedModelPreferences
import com.theemrock.smooth.sensus.inference.SharedPreferencesStore
import com.theemrock.smooth.sensus.inference.jsonSchemaToGrammar
import com.theemrock.smooth.sensus.ui.LocalModelViewModel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Bound, exported service guarded by the signature permission declared in :smooth-bridge. */
class SmoothBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deathRecipients = ConcurrentHashMap<IBinder, IBinder.DeathRecipient>()
    private lateinit var executor: BridgeExecutor

    override fun onCreate() {
        super.onCreate()
        val runtime = (application as SmoothApplication).localModelRuntime
        val preferences = SelectedModelPreferences(
            SharedPreferencesStore(getSharedPreferences(LocalModelViewModel.PREFERENCES_NAME, MODE_PRIVATE))
        )
        val storage = ModelStorage(filesDir)
        executor = BridgeExecutor(
            runtime = runtime,
            scope = scope,
            // Cold start: load the last selected model if nothing is active yet.
            ensureModel = {
                if (runtime.activeModel() != null) {
                    true
                } else {
                    val stored = preferences.load()?.let(storage::storedModel)
                    stored != null && try {
                        runtime.switchTo(stored)
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                }
            },
            schemaToGrammar = ::jsonSchemaToGrammar
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        executor.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private val binder = object : ISmoothBridge.Stub() {
        override fun describe(): String = executor.describe()

        override fun request(json: String, cb: ISmoothCallback): String {
            val client = cb.asBinder()
            try {
                deathRecipients.computeIfAbsent(client) {
                    val recipient = IBinder.DeathRecipient {
                        deathRecipients.remove(client)
                        executor.cancelClient(client)
                    }
                    client.linkToDeath(recipient, 0)
                    recipient
                }
            } catch (_: RemoteException) {
                // Client is already gone; nothing to deliver to.
                return ""
            }
            return executor.submit(json, RemoteSink(cb, client), client)
        }

        override fun cancel(requestId: String): Boolean = executor.cancel(requestId)
    }

    private inner class RemoteSink(private val cb: ISmoothCallback, private val client: IBinder) : BridgeSink {
        override fun onChunk(requestId: String, text: String) = deliver { cb.onChunk(requestId, text) }
        override fun onResult(requestId: String, json: String) = deliver { cb.onResult(requestId, json) }
        override fun onError(requestId: String, code: String, message: String) = deliver { cb.onError(requestId, code, message) }

        private inline fun deliver(call: () -> Unit) {
            try {
                call()
            } catch (_: RemoteException) {
                executor.cancelClient(client)
            }
        }
    }
}
