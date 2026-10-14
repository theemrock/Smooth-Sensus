package com.theemrock.smooth.sensus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiJsonDocumentImportTest {

    @Test
    fun `selected document text is sent unchanged to the existing importer`() {
        val json = """[{"packageName":"com.example.app","category":"MEDIA","role":"PLAYER","capabilities":[]}]"""
        var importedText: String? = null

        val success = importAiJsonDocument(
            readText = { json },
            importJson = { text ->
                importedText = text
                true
            }
        )

        assertTrue(success)
        assertEquals(json, importedText)
    }

    @Test
    fun `invalid document content reports importer failure without throwing`() {
        val success = importAiJsonDocument(
            readText = { "not-json" },
            importJson = { false }
        )

        assertFalse(success)
    }

    @Test
    fun `document read failure returns false without invoking importer`() {
        var importerInvoked = false

        val success = importAiJsonDocument(
            readText = { error("cannot read") },
            importJson = {
                importerInvoked = true
                true
            }
        )

        assertFalse(success)
        assertFalse(importerInvoked)
    }
}
