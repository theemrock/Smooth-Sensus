package com.theemrock.smooth.sensus.ui

import android.app.Application
import com.theemrock.smooth.sensus.inference.ActiveModel
import com.theemrock.smooth.sensus.inference.InvalidModelSelection
import com.theemrock.smooth.sensus.inference.LocalInferenceException
import com.theemrock.smooth.sensus.inference.LocalModelRuntimeBoundary
import com.theemrock.smooth.sensus.inference.ModelCopyProgress
import com.theemrock.smooth.sensus.inference.ModelCopyResult
import com.theemrock.smooth.sensus.inference.ModelDocument
import com.theemrock.smooth.sensus.inference.ModelDocumentSource
import com.theemrock.smooth.sensus.inference.ModelImporter
import com.theemrock.smooth.sensus.inference.PreferenceStore
import com.theemrock.smooth.sensus.inference.SelectedModelPreferences
import com.theemrock.smooth.sensus.inference.SelectedModelRecord
import com.theemrock.smooth.sensus.inference.StoredModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `saved metadata restores as available but not active`() {
        val savedModel = storedModel("saved.gguf", 12)
        val preferences = preferences().apply {
            save(SelectedModelRecord("content://saved", "saved.gguf", 12, "saved.gguf"))
        }

        val viewModel = viewModel(importer = FakeImporter(savedModel), preferences = preferences)

        assertEquals(LocalModelPhase.NO_ACTIVE_MODEL, viewModel.uiState.value.phase)
        assertEquals("saved.gguf", viewModel.uiState.value.selectedFilename)
        assertEquals(12L, viewModel.uiState.value.modelSizeBytes)
        assertTrue(viewModel.uiState.value.hasStoredCopy)
        assertFalse(viewModel.uiState.value.hasActiveModel)
    }

    @Test
    fun `provider metadata failure becomes UI error without runtime call`() = runBlocking {
        val runtime = FakeRuntime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = object : ModelDocumentSource {
            override val document: ModelDocument
                get() = throw SecurityException("provider denied private/path")
            override fun openInputStream(): InputStream = error("must not open")
        }
        val viewModel = viewModel(FakeImporter(storedModel("unused.gguf", 1)), runtime, scope = scope)

        requireNotNull(viewModel.selectModel("content://private", source, false)).join()

        assertEquals(LocalModelPhase.ERROR, viewModel.uiState.value.phase)
        assertEquals("Seçilen model bilgileri okunamadı", viewModel.uiState.value.errorMessage)
        assertEquals(0, runtime.switchCount)
        assertFalse(viewModel.uiState.value.status.contains("private"))
        Unit
    }

    @Test
    fun `selection reports copy progress then loads and persists model`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val model = storedModel("selected.gguf", 100)
        val importer = FakeImporter(model, gate = gate)
        val runtime = FakeRuntime()
        val preferences = preferences()
        val viewModel = viewModel(importer, runtime, preferences, this)

        val job = requireNotNull(viewModel.selectModel("content://selected", source("selected.gguf", 100), true))
        withTimeout(2_000) {
            while (viewModel.uiState.value.copiedBytes != 40L) delay(1)
        }
        assertEquals(LocalModelPhase.COPYING, viewModel.uiState.value.phase)
        assertEquals(40, viewModel.uiState.value.copiedBytes)
        gate.complete(Unit)
        job.join()

        assertEquals(LocalModelPhase.READY, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.hasActiveModel)
        assertEquals("selected.gguf", runtime.active?.displayName)
        assertEquals("content://selected", preferences.load()?.uri)
        Unit
    }

    @Test
    fun `copy reuse is visible and selection still loads`() = runBlocking {
        val model = storedModel("selected.gguf", 100)
        val viewModel = viewModel(
            importer = FakeImporter(model, reused = true),
            scope = this
        )

        requireNotNull(viewModel.selectModel("content://selected", source("selected.gguf", 100), true)).join()

        assertEquals(LocalModelPhase.READY, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.status.contains("kayıtlı kopya", ignoreCase = true))
        Unit
    }

    @Test
    fun `invalid selection becomes sanitized error and does not call runtime`() = runBlocking {
        val importer = FakeImporter(failure = InvalidModelSelection("Select a GGUF model file"))
        val runtime = FakeRuntime()
        val viewModel = viewModel(importer, runtime, scope = this)

        requireNotNull(viewModel.selectModel("content://bad/private/path", source("bad.bin", 4), false)).join()

        assertEquals(LocalModelPhase.ERROR, viewModel.uiState.value.phase)
        assertEquals("Geçerli bir GGUF modeli seçin", viewModel.uiState.value.errorMessage)
        assertEquals(0, runtime.switchCount)
        assertFalse(viewModel.uiState.value.status.contains("content://"))
        Unit
    }

    @Test
    fun `load failure leaves no active model and preserves copied metadata`() = runBlocking {
        val model = storedModel("broken.gguf", 100)
        val runtime = FakeRuntime(switchFailure = LocalInferenceException.NativeFailure("load failed"))
        val viewModel = viewModel(FakeImporter(model), runtime, scope = this)

        requireNotNull(viewModel.selectModel("content://broken", source("broken.gguf", 100), true)).join()

        assertEquals(LocalModelPhase.ERROR, viewModel.uiState.value.phase)
        assertFalse(viewModel.uiState.value.hasActiveModel)
        assertNull(viewModel.uiState.value.selectedFilename)
        assertEquals("broken.gguf", viewModel.uiState.value.candidateFilename)
        assertFalse(viewModel.uiState.value.hasStoredCopy)
        assertTrue(model.file.exists())
        Unit
    }

    @Test
    fun `failed switch from A to B keeps saved A identity while runtime becomes inactive`() = runBlocking {
        val modelA = storedModel("a.gguf", 10)
        val modelB = storedModel("b.gguf", 20)
        val importer = FakeImporter(modelA)
        val runtime = FakeRuntime()
        val preferences = preferences()
        val viewModel = viewModel(importer, runtime, preferences, this)
        requireNotNull(viewModel.selectModel("content://a", source("a.gguf", 10), true)).join()

        importer.model = modelB
        runtime.switchFailure = LocalInferenceException.NativeFailure("B load failed")
        requireNotNull(viewModel.selectModel("content://b", source("b.gguf", 20), true)).join()

        assertEquals(LocalModelPhase.ERROR, viewModel.uiState.value.phase)
        assertEquals("a.gguf", viewModel.uiState.value.selectedFilename)
        assertEquals("b.gguf", viewModel.uiState.value.candidateFilename)
        assertTrue(viewModel.uiState.value.hasStoredCopy)
        assertFalse(viewModel.uiState.value.hasActiveModel)
        assertEquals("content://a", preferences.load()?.uri)
        Unit
    }

    @Test
    fun `failed copy while A is active keeps A active and identified`() = runBlocking {
        val importer = FakeImporter(storedModel("a.gguf", 10))
        val runtime = FakeRuntime()
        val viewModel = viewModel(importer, runtime, scope = this)
        requireNotNull(viewModel.selectModel("content://a", source("a.gguf", 10), true)).join()

        importer.failure = InvalidModelSelection("Select a GGUF model file")
        requireNotNull(viewModel.selectModel("content://bad", source("bad.bin", 4), false)).join()

        assertEquals("a.gguf", viewModel.uiState.value.selectedFilename)
        assertTrue(viewModel.uiState.value.hasActiveModel)
        assertTrue(viewModel.uiState.value.hasStoredCopy)
        Unit
    }

    @Test
    fun `clearing ViewModel cancels its work without unloading application runtime`() = runBlocking {
        val runtime = FakeRuntime()
        val viewModel = readyViewModel(runtime, this)
        runtime.generateGate = CompletableDeferred()
        val generation = requireNotNull(viewModel.runPrompt("wait"))
        yield()

        val onCleared = LocalModelViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(viewModel)

        generation.join()
        assertEquals(0, runtime.unloadCount)
        assertNotNull(runtime.active)
        Unit
    }

    @Test
    fun `duplicate action is rejected while copy is in flight`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val viewModel = viewModel(FakeImporter(storedModel("model.gguf", 10), gate = gate), scope = this)

        val first = viewModel.selectModel("content://one", source("model.gguf", 10), true)
        yield()
        val duplicate = viewModel.selectModel("content://two", source("model.gguf", 10), true)

        assertNotNull(first)
        assertNull(duplicate)
        gate.complete(Unit)
        first?.join()
        Unit
    }

    @Test
    fun `normal run requires non empty raw output`() = runBlocking {
        val runtime = FakeRuntime(output = "hello")
        val viewModel = readyViewModel(runtime, this)

        requireNotNull(viewModel.runPrompt("Reply briefly.")).join()
        assertEquals(LocalModelPhase.OUTPUT, viewModel.uiState.value.phase)
        assertEquals("hello", viewModel.uiState.value.rawOutput)

        runtime.output = "  "
        requireNotNull(viewModel.runPrompt("Again.")).join()
        assertEquals(LocalModelPhase.ERROR, viewModel.uiState.value.phase)
        assertEquals("Model boş çıktı döndürdü", viewModel.uiState.value.errorMessage)
        Unit
    }

    @Test
    fun `grammar smoke requires exact generic JSON and passes grammar to runtime`() = runBlocking {
        val runtime = FakeRuntime(output = "{\"ok\":true}")
        val viewModel = readyViewModel(runtime, this)

        requireNotNull(viewModel.runGrammarSmoke()).join()

        assertEquals(LocalModelPhase.OUTPUT, viewModel.uiState.value.phase)
        assertEquals("{\"ok\":true}", viewModel.uiState.value.rawOutput)
        assertEquals("root ::= \"{\\\"ok\\\":true}\"", runtime.lastGrammar)

        runtime.output = "true"
        requireNotNull(viewModel.runGrammarSmoke()).join()
        assertEquals(LocalModelPhase.ERROR, viewModel.uiState.value.phase)
        Unit
    }

    @Test
    fun `unload returns to no active model and keeps stored selection`() = runBlocking {
        val runtime = FakeRuntime()
        val viewModel = readyViewModel(runtime, this)

        requireNotNull(viewModel.unload()).join()

        assertEquals(1, runtime.unloadCount)
        assertEquals(LocalModelPhase.NO_ACTIVE_MODEL, viewModel.uiState.value.phase)
        assertEquals("ready.gguf", viewModel.uiState.value.selectedFilename)
        assertFalse(viewModel.uiState.value.hasActiveModel)
        Unit
    }

    private suspend fun readyViewModel(runtime: FakeRuntime, scope: CoroutineScope): LocalModelViewModel {
        val viewModel = viewModel(FakeImporter(storedModel("ready.gguf", 10)), runtime, scope = scope)
        requireNotNull(viewModel.selectModel("content://ready", source("ready.gguf", 10), true)).join()
        return viewModel
    }

    private fun viewModel(
        importer: ModelImporter = FakeImporter(storedModel("default.gguf", 10)),
        runtime: FakeRuntime = FakeRuntime(),
        preferences: SelectedModelPreferences = preferences(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    ) = LocalModelViewModel(Application(), importer, runtime, preferences, scope)

    private fun storedModel(name: String, size: Long): StoredModel {
        val file = temporaryFolder.newFile(name)
        file.writeBytes(ByteArray(size.toInt()))
        return StoredModel(file, name, size)
    }

    private fun source(name: String, size: Long) = object : ModelDocumentSource {
        override val document = ModelDocument(name, size)
        override fun openInputStream(): InputStream = ByteArrayInputStream(ByteArray(size.toInt()))
    }

    private fun preferences() = SelectedModelPreferences(InMemoryPreferenceStore())

    private class FakeImporter(
        var model: StoredModel? = null,
        private val reused: Boolean = false,
        var failure: Throwable? = null,
        private val gate: CompletableDeferred<Unit>? = null
    ) : ModelImporter {
        override suspend fun import(
            source: ModelDocumentSource,
            onProgress: (ModelCopyProgress) -> Unit
        ): ModelCopyResult {
            failure?.let { throw it }
            onProgress(ModelCopyProgress(40, source.document.sizeBytes))
            gate?.await()
            return ModelCopyResult(requireNotNull(model), reused)
        }

        override fun storedModel(record: SelectedModelRecord): StoredModel? =
            model?.takeIf { it.file.name == record.storedFileName }
    }

    private class FakeRuntime(
        var output: String = "output",
        var switchFailure: Throwable? = null
    ) : LocalModelRuntimeBoundary {
        var active: ActiveModel? = null
        var switchCount = 0
        var unloadCount = 0
        var lastGrammar: String? = null
        var generateGate: CompletableDeferred<Unit>? = null
        val unloadSignal = CompletableDeferred<Unit>()

        override suspend fun switchTo(storedModel: StoredModel): ActiveModel {
            switchCount++
            active = null
            switchFailure?.let { throw it }
            return ActiveModel(storedModel.displayName, storedModel.sizeBytes).also { active = it }
        }

        override suspend fun generate(
            prompt: String,
            grammar: String?,
            enableThinking: Boolean
        ): com.theemrock.smooth.sensus.inference.InferenceResult {
            lastGrammar = grammar
            generateGate?.await()
            return com.theemrock.smooth.sensus.inference.InferenceResult(
                output,
                com.theemrock.smooth.sensus.inference.InferenceMetrics(
                    0.0, 1, 1.0, 1.0, 1, 1.0, 2.0,
                    com.theemrock.smooth.sensus.inference.InferenceTerminationReason.EOG
                )
            )
        }

        override suspend fun unload() {
            unloadCount++
            active = null
            unloadSignal.complete(Unit)
        }

        override fun activeModel(): ActiveModel? = active
    }

    private class InMemoryPreferenceStore : PreferenceStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(values: Map<String, String>) { this.values.putAll(values) }
        override fun remove(keys: Set<String>) { values.keys.removeAll(keys) }
    }
}
