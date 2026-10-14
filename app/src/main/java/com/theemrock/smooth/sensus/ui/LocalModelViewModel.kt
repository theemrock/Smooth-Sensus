package com.theemrock.smooth.sensus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theemrock.smooth.sensus.inference.InsufficientModelStorage
import com.theemrock.smooth.sensus.inference.InferenceMetrics
import com.theemrock.smooth.sensus.inference.InvalidModelSelection
import com.theemrock.smooth.sensus.inference.LocalInferenceException
import com.theemrock.smooth.sensus.inference.LocalModelRuntime
import com.theemrock.smooth.sensus.inference.LocalModelRuntimeBoundary
import com.theemrock.smooth.sensus.inference.ModelDocumentSource
import com.theemrock.smooth.sensus.inference.ModelImporter
import com.theemrock.smooth.sensus.inference.ModelStorage
import com.theemrock.smooth.sensus.inference.SelectedModelPreferences
import com.theemrock.smooth.sensus.inference.SelectedModelRecord
import com.theemrock.smooth.sensus.inference.SharedPreferencesStore
import com.theemrock.smooth.sensus.conversation.ChatRuntimeSettingsStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LocalModelPhase {
    NO_ACTIVE_MODEL,
    COPYING,
    LOADING,
    READY,
    GENERATING,
    OUTPUT,
    ERROR
}

data class LocalModelUiState(
    val phase: LocalModelPhase = LocalModelPhase.NO_ACTIVE_MODEL,
    val selectedFilename: String? = null,
    val candidateFilename: String? = null,
    val modelSizeBytes: Long? = null,
    val copiedBytes: Long = 0,
    val copyTotalBytes: Long? = null,
    val hasActiveModel: Boolean = false,
    val hasStoredCopy: Boolean = false,
    val sourcePermissionPersisted: Boolean = false,
    val status: String = "Etkin model yok",
    val rawOutput: String = "",
    val metrics: InferenceMetrics? = null,
    val enableThinking: Boolean = false,
    val maxTokens: Int = 64,
    val chatHistoryWindow: Int = 12,
    val chatMaxOutputTokens: Int = 256,
    val chatThinkingEnabled: Boolean = false,
    val errorMessage: String? = null
) {
    val isBusy: Boolean
        get() = phase == LocalModelPhase.COPYING ||
            phase == LocalModelPhase.LOADING ||
            phase == LocalModelPhase.GENERATING
}

class LocalModelViewModel internal constructor(
    application: Application,
    private val importer: ModelImporter,
    private val runtime: LocalModelRuntimeBoundary,
    private val preferences: SelectedModelPreferences,
    private val operationScope: CoroutineScope?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val chatRuntimeSettings: ChatRuntimeSettingsStore? = null,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        importer = ModelStorage(application.filesDir),
        runtime = (application as com.theemrock.smooth.sensus.SmoothApplication).localModelRuntime,
        preferences = SelectedModelPreferences(
            SharedPreferencesStore(
                application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
            )
        ),
        operationScope = null,
        chatRuntimeSettings = (application as com.theemrock.smooth.sensus.SmoothApplication).chatRuntimeSettings,
    )

    private val restoredRecord = preferences.load()
    private val restoredModel = restoredRecord?.let(importer::storedModel)
    private val restoredChatSettings = chatRuntimeSettings?.settings?.value
    private val _uiState = MutableStateFlow(
        LocalModelUiState(
            selectedFilename = restoredModel?.displayName,
            modelSizeBytes = restoredModel?.sizeBytes,
            hasStoredCopy = restoredModel != null,
            status = if (restoredModel == null) "Etkin model yok" else "Kayıtlı model kullanılabilir",
            chatHistoryWindow = restoredChatSettings?.historyWindow ?: 12,
            chatMaxOutputTokens = restoredChatSettings?.maxOutputTokens ?: 256,
            chatThinkingEnabled = restoredChatSettings?.enableThinking ?: false,
        )
    )
    val uiState: StateFlow<LocalModelUiState> = _uiState

    private var operationJob: Job? = null

    fun selectModel(
        uri: String,
        source: ModelDocumentSource,
        permissionPersisted: Boolean
    ): Job? = launchExclusive {
        _uiState.value = _uiState.value.copy(
            phase = LocalModelPhase.COPYING,
            candidateFilename = null,
            copiedBytes = 0,
            copyTotalBytes = null,
            status = "Model bilgileri okunuyor",
            rawOutput = "",
            errorMessage = null
        )
        try {
            val document = try {
                withContext(ioDispatcher) { source.document }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                throw ModelMetadataReadFailure(failure)
            }
            _uiState.value = _uiState.value.copy(
                candidateFilename = document.displayName,
                status = "Model kopyalanıyor"
            )
            val resolvedSource = ResolvedModelDocumentSource(document, source)
            val result = importer.import(resolvedSource) { progress ->
                _uiState.value = _uiState.value.copy(
                    copiedBytes = progress.copiedBytes,
                    copyTotalBytes = progress.totalBytes
                )
            }
            _uiState.value = _uiState.value.copy(
                phase = LocalModelPhase.LOADING,
                candidateFilename = result.model.displayName,
                status = "Model yükleniyor"
            )
            runtime.switchTo(result.model)
            preferences.save(
                SelectedModelRecord(
                    uri = uri,
                    displayName = result.model.displayName,
                    providerSizeBytes = document.sizeBytes,
                    storedFileName = result.model.file.name
                )
            )
            _uiState.value = _uiState.value.copy(
                phase = LocalModelPhase.READY,
                selectedFilename = result.model.displayName,
                candidateFilename = null,
                modelSizeBytes = result.model.sizeBytes,
                hasActiveModel = true,
                hasStoredCopy = true,
                sourcePermissionPersisted = permissionPersisted,
                status = if (result.reused) "Kayıtlı kopya kullanıldı; model hazır" else "Model hazır",
                rawOutput = "",
                errorMessage = null
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            showFailure(failure)
        }
    }

    fun loadSavedModel(): Job? {
        return launchExclusive {
            val record = preferences.load()
            val stored = record?.let(importer::storedModel)
            if (record == null || stored == null) {
                _uiState.value = _uiState.value.copy(hasStoredCopy = false)
                showFailure(IllegalStateException("Kayıtlı model kopyası kullanılamıyor"))
                return@launchExclusive
            }
            _uiState.value = _uiState.value.copy(
                phase = LocalModelPhase.LOADING,
                candidateFilename = stored.displayName,
                status = "Kayıtlı model yükleniyor",
                errorMessage = null
            )
            try {
                runtime.switchTo(stored)
                _uiState.value = _uiState.value.copy(
                    phase = LocalModelPhase.READY,
                    selectedFilename = stored.displayName,
                    candidateFilename = null,
                    modelSizeBytes = stored.sizeBytes,
                    hasActiveModel = true,
                    hasStoredCopy = true,
                    status = "Model hazır"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                showFailure(failure)
            }
        }
    }

    fun runPrompt(prompt: String): Job? = launchExclusive {
        if (prompt.isBlank()) {
            showFailure(IllegalArgumentException("İstem boş olamaz"))
            return@launchExclusive
        }
        runGeneration(prompt, grammar = null, expectedExact = null)
    }

    fun setThinkingEnabled(enabled: Boolean) {
        if (!_uiState.value.isBusy) _uiState.value = _uiState.value.copy(enableThinking = enabled)
    }

    fun setMaxTokens(value: Int) {
        if (!_uiState.value.isBusy) _uiState.value = _uiState.value.copy(maxTokens = value.coerceIn(16, 512) / 16 * 16)
    }

    fun setChatHistoryWindow(value: Int) {
        val bounded = value.coerceIn(4, 32)
        chatRuntimeSettings?.setHistoryWindow(bounded)
        _uiState.value = _uiState.value.copy(chatHistoryWindow = bounded)
    }

    fun setChatMaxOutputTokens(value: Int) {
        val bounded = (value.coerceIn(16, 512) / 16) * 16
        chatRuntimeSettings?.setMaxOutputTokens(bounded)
        _uiState.value = _uiState.value.copy(chatMaxOutputTokens = bounded)
    }

    fun setChatThinkingEnabled(enabled: Boolean) {
        chatRuntimeSettings?.setThinking(enabled)
        _uiState.value = _uiState.value.copy(chatThinkingEnabled = enabled)
    }

    fun runGrammarSmoke(): Job? = launchExclusive {
        runGeneration(GRAMMAR_PROMPT, GENERIC_JSON_GRAMMAR, GRAMMAR_EXPECTED_OUTPUT)
    }

    fun unload(): Job? = launchExclusive {
        runtime.unload()
        _uiState.value = _uiState.value.copy(
            phase = LocalModelPhase.NO_ACTIVE_MODEL,
            hasActiveModel = false,
            candidateFilename = null,
            status = if (_uiState.value.selectedFilename == null) {
                "Etkin model yok"
            } else {
                "Kayıtlı model kullanılabilir"
            },
            rawOutput = "",
            errorMessage = null
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(
            phase = if (runtime.activeModel() == null) LocalModelPhase.NO_ACTIVE_MODEL else LocalModelPhase.READY,
            hasActiveModel = runtime.activeModel() != null,
            status = if (runtime.activeModel() != null) {
                "Model hazır"
            } else if (_uiState.value.hasStoredCopy) {
                "Kayıtlı model kullanılabilir"
            } else {
                "Etkin model yok"
            },
            errorMessage = null
        )
    }

    private suspend fun runGeneration(prompt: String, grammar: String?, expectedExact: String?) {
        _uiState.value = _uiState.value.copy(
            phase = LocalModelPhase.GENERATING,
            status = if (grammar == null) "Çıkarım çalışıyor" else "Gramer testi çalışıyor",
            rawOutput = "",
            errorMessage = null
        )
        try {
            val result = runtime.generateWithMaxTokens(prompt, grammar, _uiState.value.enableThinking, _uiState.value.maxTokens)
            val output = result.text
            if (output.isBlank()) throw IllegalStateException("Model boş çıktı döndürdü")
            if (expectedExact != null && output != expectedExact) {
                throw IllegalStateException("Gramer çıktısı beklenen JSON ile eşleşmedi")
            }
            _uiState.value = _uiState.value.copy(
                phase = LocalModelPhase.OUTPUT,
                hasActiveModel = true,
                status = if (grammar == null) "Çıkarım tamamlandı" else "Gramer testi başarılı",
                rawOutput = output,
                metrics = result.metrics
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            showFailure(failure)
        }
    }

    private fun launchExclusive(block: suspend () -> Unit): Job? {
        if (operationJob?.isActive == true) return null
        return (operationScope ?: viewModelScope).launch { block() }.also { operationJob = it }
    }

    private fun showFailure(failure: Throwable) {
        val message = when (failure) {
            is InvalidModelSelection -> "Geçerli bir GGUF modeli seçin"
            is ModelMetadataReadFailure -> "Seçilen model bilgileri okunamadı"
            is InsufficientModelStorage -> "Seçilen model için yeterli uygulama depolama alanı yok"
            is LocalInferenceException.ModelNotFound -> "Kayıtlı model dosyası kullanılamıyor"
            is LocalInferenceException.NativeFailure -> failure.message ?: "Native model işlemi başarısız"
            is IllegalArgumentException, is IllegalStateException -> failure.message ?: "Yerel model işlemi başarısız"
            is IOException -> "Model kopyalanamadı"
            else -> "Yerel model işlemi başarısız"
        }
        _uiState.value = _uiState.value.copy(
            phase = LocalModelPhase.ERROR,
            hasActiveModel = runtime.activeModel() != null,
            status = "Yerel model hatası",
            errorMessage = message
        )
    }

    override fun onCleared() { operationJob?.cancel(); super.onCleared() }

    companion object {
        private const val PREFERENCES_NAME = "local_model_selection"
        const val GRAMMAR_PROMPT = "Return an acknowledgement."
        const val GRAMMAR_EXPECTED_OUTPUT = "{\"ok\":true}"
        const val GENERIC_JSON_GRAMMAR = "root ::= \"{\\\"ok\\\":true}\""
    }

    private class ModelMetadataReadFailure(cause: Throwable) : IOException(cause)

    private class ResolvedModelDocumentSource(
        override val document: com.theemrock.smooth.sensus.inference.ModelDocument,
        private val delegate: ModelDocumentSource
    ) : ModelDocumentSource {
        override fun openInputStream() = delegate.openInputStream()
    }
}
