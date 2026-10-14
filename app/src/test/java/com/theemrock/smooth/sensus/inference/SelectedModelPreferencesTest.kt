package com.theemrock.smooth.sensus.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedModelPreferencesTest {
    @Test
    fun `saved model metadata round trips without treating filename and size as identity`() {
        val store = InMemoryPreferenceStore()
        val preferences = SelectedModelPreferences(store)
        val record = SelectedModelRecord(
            uri = "content://provider/document/42",
            displayName = "Qwen 2.5.gguf",
            providerSizeBytes = 3_456_789L,
            storedFileName = "Qwen_2.5.gguf"
        )

        preferences.save(record)

        assertEquals(record, preferences.load())
    }

    @Test
    fun `unknown provider size round trips`() {
        val preferences = SelectedModelPreferences(InMemoryPreferenceStore())
        val record = SelectedModelRecord("content://model", "model.gguf", null, "model.gguf")

        preferences.save(record)

        assertEquals(record, preferences.load())
    }

    @Test
    fun `clear removes saved selection`() {
        val preferences = SelectedModelPreferences(InMemoryPreferenceStore())
        preferences.save(SelectedModelRecord("content://model", "model.gguf", 10, "model.gguf"))

        preferences.clear()

        assertNull(preferences.load())
    }

    @Test
    fun `incomplete record is ignored`() {
        val store = InMemoryPreferenceStore(
            mutableMapOf(
                "selected_model_uri" to "content://model",
                "selected_model_display_name" to "model.gguf"
            )
        )

        assertNull(SelectedModelPreferences(store).load())
    }

    @Test
    fun `corrupt provider size is ignored`() {
        val store = InMemoryPreferenceStore(
            mutableMapOf(
                "selected_model_uri" to "content://model",
                "selected_model_display_name" to "model.gguf",
                "selected_model_stored_file" to "model.gguf",
                "selected_model_provider_size" to "many"
            )
        )

        assertNull(SelectedModelPreferences(store).load())
    }

    private class InMemoryPreferenceStore(
        private val values: MutableMap<String, String> = mutableMapOf()
    ) : PreferenceStore {
        override fun get(key: String): String? = values[key]

        override fun put(values: Map<String, String>) {
            this.values.putAll(values)
        }

        override fun remove(keys: Set<String>) {
            values.keys.removeAll(keys)
        }
    }
}
