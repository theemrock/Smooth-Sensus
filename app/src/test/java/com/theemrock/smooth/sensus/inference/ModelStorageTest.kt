package com.theemrock.smooth.sensus.inference

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `non GGUF selection is rejected before opening the stream`() {
        val source = FakeSource(ModelDocument("notes.txt", 4), "data".toByteArray())

        assertThrows(InvalidModelSelection::class.java) {
            runBlocking { storage().import(source) }
        }

        assertEquals(0, source.openCount)
    }

    @Test
    fun `missing display name is rejected`() {
        val source = FakeSource(ModelDocument(null, 4), "data".toByteArray())

        assertThrows(InvalidModelSelection::class.java) {
            runBlocking { storage().import(source) }
        }
    }

    @Test
    fun `provider path characters cannot escape local models directory`() = runBlocking {
        val source = FakeSource(ModelDocument("../../folder\\model name.GGUF", 5), byteArrayOf(1, 2, 3, 4, 5))

        val result = storage().import(source)

        assertEquals("model_name.GGUF", result.model.file.name)
        assertEquals(
            temporaryFolder.root.resolve("local_models").canonicalFile,
            requireNotNull(result.model.file.parentFile).canonicalFile
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result.model.file.readBytes())
    }

    @Test
    fun `matching filename and known byte size reuse completed copy without reading source`() = runBlocking {
        val directory = temporaryFolder.root.resolve("local_models").apply { mkdirs() }
        directory.resolve("model.gguf").writeBytes(byteArrayOf(9, 8, 7))
        val source = FakeSource(ModelDocument("model.gguf", 3), byteArrayOf(1, 2, 3))

        val result = storage().import(source)

        assertTrue(result.reused)
        assertEquals(0, source.openCount)
        assertArrayEquals(byteArrayOf(9, 8, 7), result.model.file.readBytes())
    }

    @Test
    fun `size mismatch replaces completed copy by streaming source`() = runBlocking {
        val directory = temporaryFolder.root.resolve("local_models").apply { mkdirs() }
        directory.resolve("model.gguf").writeBytes(byteArrayOf(9))
        val source = FakeSource(ModelDocument("model.gguf", 4), byteArrayOf(1, 2, 3, 4))

        val result = storage().import(source)

        assertFalse(result.reused)
        assertEquals(1, source.openCount)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.model.file.readBytes())
    }

    @Test
    fun `unknown size never reuses existing file and reports copied total`() = runBlocking {
        val directory = temporaryFolder.root.resolve("local_models").apply { mkdirs() }
        directory.resolve("model.gguf").writeBytes(byteArrayOf(9, 9, 9))
        val source = FakeSource(ModelDocument("model.gguf", null), byteArrayOf(4, 5, 6, 7))
        val progress = mutableListOf<ModelCopyProgress>()

        val result = storage().import(source, progress::add)

        assertFalse(result.reused)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), result.model.file.readBytes())
        assertEquals(ModelCopyProgress(4, null), progress.last())
        assertEquals(4, result.model.sizeBytes)
    }

    @Test
    fun `known size emits bounded streaming progress`() = runBlocking {
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val progress = mutableListOf<ModelCopyProgress>()

        storage(copyBufferBytes = 4_096).import(
            FakeSource(ModelDocument("large.gguf", bytes.size.toLong()), bytes),
            progress::add
        )

        assertTrue(progress.size > 1)
        assertEquals(ModelCopyProgress(bytes.size.toLong(), bytes.size.toLong()), progress.last())
    }

    @Test
    fun `insufficient storage is reported before source is opened`() {
        val source = FakeSource(ModelDocument("large.gguf", 10), ByteArray(10))

        assertThrows(InsufficientModelStorage::class.java) {
            runBlocking { storage(availableBytes = { 9 }).import(source) }
        }

        assertEquals(0, source.openCount)
    }

    @Test
    fun `stream failure removes partial file and leaves previous completed file intact`() {
        val directory = temporaryFolder.root.resolve("local_models").apply { mkdirs() }
        val completed = directory.resolve("model.gguf").apply { writeBytes(byteArrayOf(9)) }
        val source = object : ModelDocumentSource {
            override val document = ModelDocument("model.gguf", 20)
            override fun openInputStream(): InputStream = FailingInputStream(IOException("provider failed"))
        }

        assertThrows(IOException::class.java) {
            runBlocking { storage().import(source) }
        }

        assertArrayEquals(byteArrayOf(9), completed.readBytes())
        assertFalse(directory.resolve("model.gguf.part").exists())
    }

    @Test
    fun `cancellation removes partial file`() {
        val source = object : ModelDocumentSource {
            override val document = ModelDocument("model.gguf", 20)
            override fun openInputStream(): InputStream = FailingInputStream(CancellationException("cancelled"))
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { storage().import(source) }
        }

        assertFalse(temporaryFolder.root.resolve("local_models/model.gguf").exists())
        assertFalse(temporaryFolder.root.resolve("local_models/model.gguf.part").exists())
    }

    @Test
    fun `cancelling import job prevents publishing a new final file`() = runBlocking {
        val started = CountDownLatch(1)
        val source = SlowFiniteSource("cancelled.gguf", started)

        val job = launch(Dispatchers.IO) { storage(copyBufferBytes = 64).import(source) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertFalse(temporaryFolder.root.resolve("local_models/cancelled.gguf").exists())
        assertFalse(temporaryFolder.root.resolve("local_models/cancelled.gguf.part").exists())
    }

    @Test
    fun `cancelling replacement preserves previous completed file`() = runBlocking {
        val directory = temporaryFolder.root.resolve("local_models").apply { mkdirs() }
        val completed = directory.resolve("model.gguf").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val started = CountDownLatch(1)
        val source = SlowFiniteSource("model.gguf", started)

        val job = launch(Dispatchers.IO) { storage(copyBufferBytes = 64).import(source) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertArrayEquals(byteArrayOf(9, 8, 7), completed.readBytes())
        assertFalse(directory.resolve("model.gguf.part").exists())
    }

    private fun storage(
        availableBytes: () -> Long = { Long.MAX_VALUE },
        copyBufferBytes: Int = 8 * 1024
    ) = ModelStorage(temporaryFolder.root, availableBytes, copyBufferBytes)

    private class FakeSource(
        override val document: ModelDocument,
        private val bytes: ByteArray
    ) : ModelDocumentSource {
        var openCount = 0

        override fun openInputStream(): InputStream {
            openCount++
            return ByteArrayInputStream(bytes)
        }
    }

    private class FailingInputStream(private val failure: RuntimeException) : InputStream() {
        constructor(failure: IOException) : this(RuntimeException(failure))

        private var emitted = false

        override fun read(): Int {
            if (!emitted) {
                emitted = true
                return 1
            }
            val cause = failure.cause
            if (cause is IOException) throw cause
            throw failure
        }
    }

    private class SlowFiniteSource(
        name: String,
        private val started: CountDownLatch
    ) : ModelDocumentSource {
        private val totalBytes = 64 * 100L
        override val document = ModelDocument(name, totalBytes)

        override fun openInputStream(): InputStream = object : InputStream() {
            private var remaining = totalBytes

            override fun read(): Int {
                if (remaining == totalBytes) started.countDown()
                if (remaining-- <= 0) return -1
                Thread.sleep(2)
                return 1
            }
        }
    }
}
