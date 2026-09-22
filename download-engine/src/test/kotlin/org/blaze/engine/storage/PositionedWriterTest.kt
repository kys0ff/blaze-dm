package org.blaze.engine.storage

import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accelerated HTTP path depends on two storage promises that did not exist before: a writer
 * that can be used from several threads at arbitrary offsets, and a size extension that does not
 * write data. If either is wrong, chunked downloads are corrupted in ways no test of the transfer
 * logic itself would explain.
 */
class PositionedWriterTest {

    private val storage: FileStorage = DefaultFileStorage()
    private val dir = Files.createTempDirectory("blaze-storage-test")

    @Test
    fun `concurrent positioned writes land at their own offsets`() {
        val file = dir.resolve("chunks.bin")
        val chunkSize = 64 * 1024
        val chunks = 32
        val writer = storage.openPositionedWriter(file)
        val pool = Executors.newFixedThreadPool(8)

        try {
            val futures = (0 until chunks).map { index ->
                pool.submit {
                    val payload = ByteArray(chunkSize) { byte -> (index * 31 + byte).toByte() }
                    var written = 0
                    while (written < payload.size) {
                        val length = minOf(8 * 1024, payload.size - written)
                        writer.writeAt(index.toLong() * chunkSize + written, payload, written, length)
                        written += length
                    }
                }
            }
            futures.forEach { it.get() }
            writer.force(true)
        } finally {
            writer.close()
            pool.shutdown()
        }

        val actual = Files.readAllBytes(file)
        assertEquals(chunks.toLong() * chunkSize, actual.size.toLong())
        for (index in 0 until chunks) {
            val start = index * chunkSize
            assertTrue(
                (0 until chunkSize).all { offset ->
                    actual[start + offset] == (index * 31 + offset).toByte()
                },
                "chunk $index was overwritten by another worker"
            )
        }
    }

    @Test
    fun `preallocating extends the file without writing data into it`() {
        val file = dir.resolve("sparse.bin")
        Files.createFile(file)

        storage.preallocate(file, 4L * 1024 * 1024)

        assertEquals(4L * 1024 * 1024, Files.size(file))
        // Out-of-order chunk writes must be able to land anywhere in the pre-allocated range.
        storage.openPositionedWriter(file).use { writer ->
            writer.writeAt(3L * 1024 * 1024, byteArrayOf(1, 2, 3), 0, 3)
            assertEquals(4L * 1024 * 1024, writer.size(), "writing high shifted the end of the file")
        }
        RandomAccessFile(file.toFile(), "r").use { raf ->
            raf.seek(3L * 1024 * 1024)
            assertEquals(1, raf.read(), "the bytes written high in the range did not arrive")
            assertEquals(2, raf.read())
        }
    }

    @Test
    fun `a smaller preallocate leaves the file alone`() {
        val file = dir.resolve("already-big.bin")
        Files.write(file, ByteArray(8 * 1024))

        storage.preallocate(file, 1024L)

        assertEquals(8L * 1024, Files.size(file), "preallocate truncated a longer file")
    }

    @Test
    fun `the resume sidecar sits next to the partial file`() {
        val destination = Files.createDirectories(dir.resolve("Media")).resolve("archive.zip")

        val partial = storage.getPartialFile(destination).toPath()
        val state = storage.getResumeStateFile(destination).toPath()

        assertEquals("archive.zip.part", partial.fileName.toString())
        assertEquals(partial.parent, state.parent, "the sidecar must travel with the partial file")
        assertEquals("archive.zip.meta", state.fileName.toString())
    }
}
