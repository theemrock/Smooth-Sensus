package com.theemrock.smooth.sensus.inference

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ModelDocument(
    val displayName: String?,
    val sizeBytes: Long?
)

interface ModelDocumentSource {
    val document: ModelDocument
    fun openInputStream(): InputStream
}

data class ModelCopyProgress(
    val copiedBytes: Long,
    val totalBytes: Long?
)

data class StoredModel(
    val file: File,
    val displayName: String,
    val sizeBytes: Long
)

data class ModelCopyResult(
    val model: StoredModel,
    val reused: Boolean
)

class InvalidModelSelection(message: String) : IllegalArgumentException(message)

class InsufficientModelStorage(
    val requiredBytes: Long,
    val availableBytes: Long
) : IOException("Not enough app storage for the selected model")

interface ModelImporter {
    suspend fun import(
        source: ModelDocumentSource,
        onProgress: (ModelCopyProgress) -> Unit = {}
    ): ModelCopyResult

    fun storedModel(record: SelectedModelRecord): StoredModel? = null
}

class ModelStorage(
    rootDirectory: File,
    private val availableBytes: () -> Long = { rootDirectory.usableSpace },
    private val copyBufferBytes: Int = DEFAULT_COPY_BUFFER_BYTES
) : ModelImporter {
    private val modelDirectory = rootDirectory.resolve(MODEL_DIRECTORY)

    init {
        require(copyBufferBytes in 1..MAX_COPY_BUFFER_BYTES)
    }

    override suspend fun import(
        source: ModelDocumentSource,
        onProgress: (ModelCopyProgress) -> Unit
    ): ModelCopyResult = withContext(Dispatchers.IO) {
        val displayName = validatedDisplayName(source.document.displayName)
        val target = modelDirectory.resolve(displayName)
        val expectedSize = source.document.sizeBytes?.takeIf { it >= 0 }

        if (expectedSize != null && target.isFile && target.length() == expectedSize) {
            return@withContext ModelCopyResult(
                StoredModel(target, displayName, expectedSize),
                reused = true
            )
        }

        if (expectedSize != null) {
            val freeBytes = availableBytes()
            if (freeBytes < expectedSize) {
                throw InsufficientModelStorage(expectedSize, freeBytes)
            }
        }

        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IOException("App-private model directory could not be created")
        }
        val canonicalDirectory = modelDirectory.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget.parentFile != canonicalDirectory) {
            throw InvalidModelSelection("Selected model filename is not safe")
        }

        val partial = modelDirectory.resolve("$displayName.part")
        if (partial.exists() && !partial.delete()) {
            throw IOException("Previous partial model copy could not be removed")
        }

        try {
            var copied = 0L
            source.openInputStream().buffered(copyBufferBytes).use { input ->
                partial.outputStream().buffered(copyBufferBytes).use { output ->
                    val buffer = ByteArray(copyBufferBytes)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(ModelCopyProgress(copied, expectedSize))
                    }
                }
            }
            if (expectedSize != null && copied != expectedSize) {
                throw IOException("Selected model size changed while copying")
            }
            currentCoroutineContext().ensureActive()
            moveCompletedCopy(partial, target)
            ModelCopyResult(
                StoredModel(target, displayName, copied),
                reused = false
            )
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    override fun storedModel(record: SelectedModelRecord): StoredModel? {
        val safeName = runCatching { validatedDisplayName(record.storedFileName) }.getOrNull()
            ?: return null
        val file = modelDirectory.resolve(safeName)
        if (!file.isFile || requireNotNull(file.parentFile).canonicalFile != modelDirectory.canonicalFile) return null
        return StoredModel(file, record.displayName, file.length())
    }

    private fun validatedDisplayName(rawName: String?): String {
        val leafName = rawName
            ?.trim()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: throw InvalidModelSelection("Selected document has no filename")
        if (!leafName.endsWith(".gguf", ignoreCase = true)) {
            throw InvalidModelSelection("Select a GGUF model file")
        }
        val safeName = leafName.replace(UNSAFE_FILENAME_CHARS, "_")
        if (safeName == ".gguf") {
            throw InvalidModelSelection("Selected model filename is invalid")
        }
        return safeName
    }

    private fun moveCompletedCopy(partial: File, target: File) {
        try {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val MODEL_DIRECTORY = "local_models"
        const val DEFAULT_COPY_BUFFER_BYTES = 1024 * 1024
        const val MAX_COPY_BUFFER_BYTES = 8 * 1024 * 1024
        val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
