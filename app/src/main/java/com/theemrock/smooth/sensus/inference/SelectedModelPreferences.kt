package com.theemrock.smooth.sensus.inference

import android.content.SharedPreferences

data class SelectedModelRecord(
    val uri: String,
    val displayName: String,
    val providerSizeBytes: Long?,
    val storedFileName: String
)

interface PreferenceStore {
    fun get(key: String): String?
    fun put(values: Map<String, String>)
    fun remove(keys: Set<String>)
}

class SelectedModelPreferences(private val store: PreferenceStore) {
    fun save(record: SelectedModelRecord) {
        store.put(
            mapOf(
                KEY_URI to record.uri,
                KEY_DISPLAY_NAME to record.displayName,
                KEY_PROVIDER_SIZE to (record.providerSizeBytes?.toString() ?: UNKNOWN_SIZE),
                KEY_STORED_FILE to record.storedFileName
            )
        )
    }

    fun load(): SelectedModelRecord? {
        val uri = store.get(KEY_URI)?.takeIf { it.isNotBlank() } ?: return null
        val displayName = store.get(KEY_DISPLAY_NAME)?.takeIf { it.isNotBlank() } ?: return null
        val storedFile = store.get(KEY_STORED_FILE)?.takeIf { it.isNotBlank() } ?: return null
        val sizeValue = store.get(KEY_PROVIDER_SIZE) ?: return null
        val parsedSize = sizeValue.toLongOrNull() ?: return null
        if (parsedSize < -1) return null
        return SelectedModelRecord(
            uri = uri,
            displayName = displayName,
            providerSizeBytes = parsedSize.takeIf { it >= 0 },
            storedFileName = storedFile
        )
    }

    fun clear() = store.remove(ALL_KEYS)

    private companion object {
        const val KEY_URI = "selected_model_uri"
        const val KEY_DISPLAY_NAME = "selected_model_display_name"
        const val KEY_PROVIDER_SIZE = "selected_model_provider_size"
        const val KEY_STORED_FILE = "selected_model_stored_file"
        const val UNKNOWN_SIZE = "-1"
        val ALL_KEYS = setOf(KEY_URI, KEY_DISPLAY_NAME, KEY_PROVIDER_SIZE, KEY_STORED_FILE)
    }
}

class SharedPreferencesStore(
    private val preferences: SharedPreferences
) : PreferenceStore {
    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(values: Map<String, String>) {
        preferences.edit().apply {
            values.forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    override fun remove(keys: Set<String>) {
        preferences.edit().apply {
            keys.forEach(::remove)
        }.apply()
    }
}
