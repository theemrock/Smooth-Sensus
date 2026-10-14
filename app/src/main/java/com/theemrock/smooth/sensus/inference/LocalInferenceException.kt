package com.theemrock.smooth.sensus.inference

sealed class LocalInferenceException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    class ModelNotFound : LocalInferenceException("Model file does not exist or is not readable")
    class NativeFailure(message: String) : LocalInferenceException(message)
}
