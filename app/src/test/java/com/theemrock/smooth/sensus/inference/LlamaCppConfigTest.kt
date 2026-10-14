package com.theemrock.smooth.sensus.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LlamaCppConfigTest {
    @Test
    fun `default generation settings are bounded and CPU suitable`() {
        val config = LlamaCppConfig(modelPath = "/models/model.gguf")

        assertEquals(4096, config.contextSize)
        assertEquals(Runtime.getRuntime().availableProcessors().coerceAtLeast(1), config.threadCount)
        assertEquals(256, config.maxTokens)
        assertEquals(0.0f, config.temperature)
        assertEquals(0.95f, config.topP)
        assertEquals(40, config.topK)
        assertEquals(0, config.seed)
    }

    @Test fun `blank model path is rejected`() = assertInvalid { LlamaCppConfig(modelPath = "  ") }
    @Test fun `non-positive context size is rejected`() = assertInvalid { valid(contextSize = 0) }
    @Test fun `non-positive thread count is rejected`() = assertInvalid { valid(threadCount = 0) }
    @Test fun `non-positive max tokens is rejected`() = assertInvalid { valid(maxTokens = 0) }
    @Test fun `negative temperature is rejected`() = assertInvalid { valid(temperature = -0.1f) }
    @Test fun `non-finite temperature is rejected`() = assertInvalid { valid(temperature = Float.NaN) }
    @Test fun `top p outside zero exclusive to one inclusive is rejected`() {
        assertInvalid { valid(topP = 0.0f) }
        assertInvalid { valid(topP = 1.1f) }
    }
    @Test fun `non-positive top k is rejected`() = assertInvalid { valid(topK = 0) }

    private fun valid(
        contextSize: Int = 4096,
        threadCount: Int = 4,
        maxTokens: Int = 256,
        temperature: Float = 0.0f,
        topP: Float = 0.95f,
        topK: Int = 40
    ) = LlamaCppConfig(
        modelPath = "/models/model.gguf",
        contextSize = contextSize,
        threadCount = threadCount,
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
        topK = topK
    )

    private fun assertInvalid(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }
}
