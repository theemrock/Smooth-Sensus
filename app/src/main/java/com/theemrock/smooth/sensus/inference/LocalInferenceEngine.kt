package com.theemrock.smooth.sensus.inference

data class InferenceChatMessage(val role: String, val content: String)

fun interface InferenceTextChunkCallback {
    fun onTextChunk(chunk: String)
}

enum class InferenceTerminationReason { EOG, MAX_TOKENS, CONTEXT_FULL, CANCELLED, ERROR }

data class InferenceMetrics(
    val modelLoadDurationMs: Double,
    val promptTokenCount: Int,
    val promptEvalDurationMs: Double,
    val timeToFirstTokenMs: Double,
    val generatedTokenCount: Int,
    val generationDurationMs: Double,
    val totalInferenceDurationMs: Double,
    val terminationReason: InferenceTerminationReason,
    val templateApplied: Boolean = true,
    val contextUsed: Int = 0,
    val contextMax: Int = 0
) {
    val contextUsageRatio: Double
        get() = if (contextMax > 0) contextUsed.toDouble() / contextMax else 0.0
    val promptTokensPerSecond: Double
        get() = if (promptEvalDurationMs > 0) promptTokenCount * 1000.0 / promptEvalDurationMs else 0.0
    val generationTokensPerSecond: Double
        get() = if (generationDurationMs > 0) generatedTokenCount * 1000.0 / generationDurationMs else 0.0
}

data class InferenceResult(val text: String, val metrics: InferenceMetrics)
