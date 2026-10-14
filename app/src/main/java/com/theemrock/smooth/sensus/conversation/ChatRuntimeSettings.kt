package com.theemrock.smooth.sensus.conversation

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ChatRuntimeSettings(
    val historyWindow: Int = 12,
    val maxOutputTokens: Int = 256,
    val enableThinking: Boolean = false,
)

class ChatRuntimeSettingsStore(private val preferences: SharedPreferences) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ChatRuntimeSettings> = _settings

    fun setHistoryWindow(value: Int) = save(_settings.value.copy(historyWindow = value.coerceIn(4, 32)))
    fun setMaxOutputTokens(value: Int) = save(_settings.value.copy(maxOutputTokens = (value.coerceIn(16, 512) / 16) * 16))
    fun setThinking(enabled: Boolean) = save(_settings.value.copy(enableThinking = enabled))

    private fun load() = ChatRuntimeSettings(
        historyWindow = preferences.getInt(KEY_HISTORY, 12).coerceIn(4, 32),
        maxOutputTokens = (preferences.getInt(KEY_MAX_OUTPUT, 256).coerceIn(16, 512) / 16) * 16,
        enableThinking = preferences.getBoolean(KEY_THINKING, false),
    )

    private fun save(value: ChatRuntimeSettings) {
        preferences.edit()
            .putInt(KEY_HISTORY, value.historyWindow)
            .putInt(KEY_MAX_OUTPUT, value.maxOutputTokens)
            .putBoolean(KEY_THINKING, value.enableThinking)
            .apply()
        _settings.value = value
    }

    private companion object {
        const val KEY_HISTORY = "chat_history_window"
        const val KEY_MAX_OUTPUT = "chat_max_output_tokens"
        const val KEY_THINKING = "chat_thinking"
    }
}
