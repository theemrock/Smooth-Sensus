package com.theemrock.smooth.sensus.inference

data class LlamaCppConfig(
    val modelPath: String,
    val contextSize: Int = 4096,
    val threadCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    val maxTokens: Int = 256,
    val temperature: Float = 0.0f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val seed: Int = 0,
    val useJinjaTemplate: Boolean = true
) {
    init {
        require(modelPath.isNotBlank()) { "modelPath must not be blank" }
        require(contextSize > 0) { "contextSize must be positive" }
        require(threadCount > 0) { "threadCount must be positive" }
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(temperature.isFinite() && temperature >= 0.0f) {
            "temperature must be finite and non-negative"
        }
        require(topP.isFinite() && topP > 0.0f && topP <= 1.0f) {
            "topP must be in (0, 1]"
        }
        require(topK > 0) { "topK must be positive" }
    }
}
