package org.blaze.engine.benchmark

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.core.DownloadManager
import org.blaze.engine.execution.HttpDownloadCoordinator
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.support.TestHttpServer
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Measured evidence for the two HTTP changes that are supposed to buy throughput: several
 * connections against a server that caps each one, and a single positioned file channel instead of
 * reopening the file for every buffer.
 *
 * They are skipped in the normal test run (they cost seconds and their numbers depend on the
 * machine); run them with `./gradlew :download-engine:httpBenchmark`.
 */
class HttpThroughputBenchmark {

    private val mib = 1024 * 1024
    private val enabled get() = System.getProperty("blaze.benchmark") == "true"
    private val storage = DefaultFileStorage()
    private val dir: Path = Files.createTempDirectory("blaze-http-bench")

    private val client: HttpClient = DownloadManager.createDefaultHttpClient()

    @AfterTest
    fun tearDown() {
        client.close()
        dir.toFile().deleteRecursively()
    }

    private data class Sample(val mebibytesPerSecond: Double, val seconds: Double, val served: Long)

    private fun measure(connections: Int, perConnectionKib: Long, sizeMib: Int): Sample {
        val payload = TestHttpServer.of(sizeMib * mib, seed = 99)
        TestHttpServer(payload, perConnectionBytesPerSec = perConnectionKib * 1024).use { server ->
            val destination = dir.resolve("bench-$connections.bin")
            val partial = storage.getPartialFile(destination).toPath()
            val coordinator = HttpDownloadCoordinator(
                client = HttpNetworkClient(client),
                storage = storage,
                limiter = BandwidthLimiter(),
                settings = DownloadSettings()
            )

            val started = System.nanoTime()
            val outcome = runBlocking {
                coordinator.download(
                    request = DownloadRequest.Http("bench", server.url, destination),
                    destination = destination,
                    partial = partial,
                    connections = connections
                ) {}
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
            assertIs<HttpDownloadCoordinator.Outcome.Success>(outcome)
            Files.deleteIfExists(partial)

            return Sample(
                mebibytesPerSecond = payload.size / mib / elapsed,
                seconds = elapsed,
                served = server.bytesServed.get()
            )
        }
    }

    @Test
    fun `several connections beat one against a per-connection cap`() {
        if (!enabled) return

        // 4 MiB/s per connection is the shape of a CDN or a throttling proxy: the file is only
        // reachable at 4 MiB/s no matter how fast the link is, unless the work is split.
        val perConnection = 4L * 1024
        val single = measure(connections = 1, perConnectionKib = perConnection, sizeMib = 12)
        // Warm the pool once so the parallel run does not pay for the first connection twice.
        measure(connections = 4, perConnectionKib = perConnection, sizeMib = 12)
        val parallel = measure(connections = 4, perConnectionKib = perConnection, sizeMib = 12)

        println(
            "HTTP throughput (12 MiB, 4 MiB/s per connection): " +
                "1 connection = %.2f MiB/s in %.2f s, 4 connections = %.2f MiB/s in %.2f s (%.2fx)".format(
                single.mebibytesPerSecond, single.seconds,
                parallel.mebibytesPerSecond, parallel.seconds,
                parallel.mebibytesPerSecond / single.mebibytesPerSecond
            )
        )

        assertTrue(
            parallel.mebibytesPerSecond > single.mebibytesPerSecond * 1.5,
            "expected at least 1.5x from 4 connections, got %.2fx".format(
                parallel.mebibytesPerSecond / single.mebibytesPerSecond
            )
        )
    }

    @Test
    fun `the write path moves as fast with fewer, larger buffers`() {
        if (!enabled) return

        val size = 16 * mib
        val source = ByteArray(size)                      // stands in for the socket's bytes
        val legacy = dir.resolve("legacy.bin")
        val current = dir.resolve("current.bin")

        // Old path: 8 KiB per read, a fresh copyOfRange for every one of them, and a
        // RandomAccessFile opened, seeked, written and closed around each chunk.
        val legacyNs = measureTime {
            var offset = 0L
            while (offset < size) {
                val length = minOf(8 * 1024, size - offset.toInt())
                val copy = source.copyOfRange(offset.toInt(), offset.toInt() + length)
                RandomAccessFile(legacy.toFile(), "rw").use { raf ->
                    raf.seek(offset)
                    raf.write(copy)
                }
                offset += length
            }
        }

        // New path: one buffer, one channel, positioned writes.
        val currentNs = measureTime {
            val buffer = java.nio.ByteBuffer.allocate(128 * 1024)
            FileChannel.open(current, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { channel ->
                var offset = 0
                while (offset < size) {
                    val length = minOf(128 * 1024, size - offset)
                    buffer.clear()
                    buffer.put(source, offset, length)
                    buffer.flip()
                    channel.write(buffer, offset.toLong())
                    offset += length
                }
                channel.force(true)
            }
        }

        assertEquals(size.toLong(), Files.size(legacy))
        assertEquals(size.toLong(), Files.size(current))
        println(
            (
                "Disk write path (16 MiB): 8 KiB + copyOfRange + reopen = %.1f ms (%.1f MiB/s), " +
                "128 KiB + one channel = %.1f ms (%.1f MiB/s)"
            ).format(
                legacyNs / 1e6, size / mib / (legacyNs / 1e9),
                currentNs / 1e6, size / mib / (currentNs / 1e9)
            )
        )
        // The saving comes from handing the kernel fewer, larger buffers, not from the positioned
        // write itself: on a machine with the page cache in front of the disk, reopening the file
        // per 8 KiB chunk is barely visible (10.8 vs 11.8 ms when both sides use 8 KiB writes).
        assertTrue(
            currentNs < legacyNs,
            "128 KiB positioned writes were slower than 8 KiB reopen/seek/close: %.1f ms vs %.1f ms".format(
                currentNs / 1e6, legacyNs / 1e6
            )
        )
    }

    private inline fun measureTime(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return System.nanoTime() - started
    }
}
