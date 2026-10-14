package com.theemrock.smooth.sensus.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.theemrock.smooth.sensus.inference.AndroidModelDocumentSource
import com.theemrock.smooth.sensus.inference.takePersistableModelReadPermission
import com.theemrock.smooth.sensus.R
import java.util.Locale

@Composable
fun LocalModelTestRoute(viewModel: LocalModelViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var prompt by rememberSaveable { mutableStateOf(DEFAULT_SMOKE_PROMPT) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val permissionPersisted = takePersistableModelReadPermission(context.contentResolver, uri)
            viewModel.selectModel(
                uri = uri.toString(),
                source = AndroidModelDocumentSource(context.contentResolver, uri),
                permissionPersisted = permissionPersisted
            )
        }
    }

    LocalModelTestScreen(
        state = state,
        prompt = prompt,
        onPromptChange = { prompt = it },
        onSelectModel = {
            picker.launch(arrayOf("application/octet-stream", "application/x-gguf", "application/*"))
        },
        onLoadSavedModel = { viewModel.loadSavedModel() },
        onRun = { viewModel.runPrompt(prompt) },
        onThinkingChange = viewModel::setThinkingEnabled,
        onMaxTokensChange = viewModel::setMaxTokens,
        onChatHistoryWindowChange = viewModel::setChatHistoryWindow,
        onChatMaxOutputTokensChange = viewModel::setChatMaxOutputTokens,
        onChatThinkingChange = viewModel::setChatThinkingEnabled,
        onGrammarSmoke = { viewModel.runGrammarSmoke() },
        onUnload = { viewModel.unload() }
    )
}

@Composable
fun LocalModelTestScreen(
    state: LocalModelUiState,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onSelectModel: () -> Unit,
    onLoadSavedModel: () -> Unit,
    onRun: () -> Unit,
    onThinkingChange: (Boolean) -> Unit = {},
    onMaxTokensChange: (Int) -> Unit = {},
    onChatHistoryWindowChange: (Int) -> Unit = {},
    onChatMaxOutputTokensChange: (Int) -> Unit = {},
    onChatThinkingChange: (Boolean) -> Unit = {},
    onGrammarSmoke: () -> Unit,
    onUnload: () -> Unit
) {
    val canUseModel = state.hasActiveModel && !state.isBusy
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.local_model_test), style = MaterialTheme.typography.headlineSmall)
        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.selectedFilename ?: stringResource(R.string.no_gguf_selected), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val readiness = if (canUseModel && state.errorMessage == null) "Hazır" else state.status
                Text(listOfNotNull(state.modelSizeBytes?.let(::formatByteCount), readiness.takeIf(String::isNotBlank)).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onSelectModel, enabled = !state.isBusy) { Text(if (state.hasActiveModel) "Değiştir" else stringResource(R.string.select_gguf)) }
                    if (state.hasActiveModel) OutlinedButton(onClick = onUnload, enabled = !state.isBusy) { Text("Kapat") }
                }
                if (!state.hasActiveModel && state.hasStoredCopy) OutlinedButton(onClick = onLoadSavedModel, enabled = !state.isBusy) { Text(stringResource(R.string.load_stored_copy)) }
            }
        }

        if (state.phase == LocalModelPhase.COPYING) {
            val total = state.copyTotalBytes
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (state.copiedBytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("${formatByteCount(state.copiedBytes)} / ${formatByteCount(total)}")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.bytes_copied, formatByteCount(state.copiedBytes)))
            }
        }

        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            enabled = !state.isBusy,
            label = { Text(stringResource(R.string.prompt)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(onClick = { onThinkingChange(!state.enableThinking) }, enabled = !state.isBusy) { Text("Thinking: ${if (state.enableThinking) "ON" else "OFF"}") }
        Text("Max tokens: ${state.maxTokens}")
        Slider(
            value = state.maxTokens.toFloat(),
            onValueChange = { onMaxTokensChange((it / 16).toInt() * 16) },
            valueRange = 16f..512f,
            steps = 30,
            enabled = !state.isBusy
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun, enabled = canUseModel && prompt.isNotBlank()) {
                Text(stringResource(R.string.run))
            }
            Button(onClick = onGrammarSmoke, enabled = canUseModel) {
                Text(stringResource(R.string.grammar_test))
            }
        }

        HorizontalDivider()
        Text("CHAT RUNTIME", style = MaterialTheme.typography.titleMedium)
        Text("History window: ${state.chatHistoryWindow}")
        Slider(
            value = state.chatHistoryWindow.toFloat(),
            onValueChange = { onChatHistoryWindowChange(it.toInt()) },
            valueRange = 4f..32f,
            steps = 27,
        )
        Text("Max output tokens: ${state.chatMaxOutputTokens}")
        Slider(
            value = state.chatMaxOutputTokens.toFloat(),
            onValueChange = { onChatMaxOutputTokensChange((it / 16).toInt() * 16) },
            valueRange = 16f..512f,
            steps = 30,
        )
        OutlinedButton(onClick = { onChatThinkingChange(!state.chatThinkingEnabled) }) {
            Text("Chat THINK: ${if (state.chatThinkingEnabled) "ON" else "OFF"}")
        }

        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.raw_output), style = MaterialTheme.typography.titleMedium)
        Text(state.rawOutput.ifEmpty { "—" })
        state.metrics?.let { metrics ->
            Text(
                String.format(
                    Locale.US,
                    "load %.0f ms | prompt %d / %.2f s / %.2f tok/s | TTFT %.0f ms\ngenerated %d / %.2f s / %.2f tok/s | total %.2f s | %s",
                    metrics.modelLoadDurationMs,
                    metrics.promptTokenCount,
                    metrics.promptEvalDurationMs / 1000.0,
                    metrics.promptTokensPerSecond,
                    metrics.timeToFirstTokenMs,
                    metrics.generatedTokenCount,
                    metrics.generationDurationMs / 1000.0,
                    metrics.generationTokensPerSecond,
                    metrics.totalInferenceDurationMs / 1000.0,
                    metrics.terminationReason.name
                )
            )
        }
    }
}

internal fun formatByteCount(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private const val DEFAULT_SMOKE_PROMPT = "Reply with one short word."
