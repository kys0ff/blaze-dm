package org.blaze.engine.archive

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.support.TestStaticServer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises remote selective retrieval over a real ranged HTTP server: it reads only the central
 * directory and the byte ranges of the chosen entries, proving that unselected members are never pulled
 * down. This is the counterpart to the in-memory [RemoteZipTest] unit tests and is what makes the
 * "download one file out of a big archive" promise trustworthy.
 *
 * The archive is deliberately large and *incompressible* (deterministic pseudo-random bytes) so the
 * fixed-size end-of-central-directory search window is a small fraction of the file — otherwise every
 * inspection would degenerate into reading the whole thing and the partial-read guarantee would be a
 * vacuous one.
 */
class RemoteZipHttpIntegrationTest {

    private val readme = randomBytes(20_000, seed = 99)
    private val blob = randomBytes(300_000, seed = 1234)
    private val nested = "deep/nested/entry-payload".toByteArray()

    private fun randomBytes(size: Int, seed: Int): ByteArray {
        val data = ByteArray(size)
        var state = seed
        for (i in data.indices) {
            state = state * 1103515245 + 12345
            data[i] = (state ushr 16).toByte()
        }
        return data
    }

    private fun buildZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("docs/readme.bin")); zip.write(readme); zip.closeEntry()
            zip.putNextEntry(ZipEntry("data/blob.bin")); zip.write(blob); zip.closeEntry()
            zip.putNextEntry(ZipEntry("a/b/c/deep.txt")); zip.write(nested); zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `inspect reads only the central directory and marks selective retrieval supported`(): Unit = runBlocking {
        val zip = buildZip()
        TestStaticServer(mapOf("/a.zip" to zip)).use { server ->
            val client = HttpNetworkClient(HttpClient(CIO))
            val source = HttpRemoteByteSource(client, server.url("/a.zip"))
            val listing = ZipCentralDirectory.inspect(source)
            assertEquals(3, listing.entries.size)
            assertTrue(source.rangeSupported, "server proved ranged reads")
            assertTrue(listing.selectiveSupported)
            assertNotNull(listing.entryNamed("docs/readme.bin"))
            // Inspection pulls a bounded tail + the directory, never the whole archive.
            assertTrue(
                server.bytesServed.get() < zip.size.toLong(),
                "central-directory read is partial (${server.bytesServed} < ${zip.size})"
            )
        }
    }

    @Test
    fun `extracting one entry writes it and fetches none of the large unselected entry`(): Unit = runBlocking {
        val zip = buildZip()
        TestStaticServer(mapOf("/a.zip" to zip)).use { server ->
            val client = HttpNetworkClient(HttpClient(CIO))
            val source = HttpRemoteByteSource(client, server.url("/a.zip"))
            val listing = ZipCentralDirectory.inspect(source)
            val readmeEntry = requireNotNull(listing.entryNamed("docs/readme.bin"))
            val dest = Files.createTempDirectory("zip-http-extract")
            try {
                val beforeExtract = server.bytesServed.get()
                val result = RemoteZipExtractor(source).extract(listOf(readmeEntry), dest)
                assertTrue(result.allSucceeded, "extraction failures: ${result.failures}")
                assertContentEquals(readme, Files.readAllBytes(dest.resolve("docs/readme.bin")))
                assertFalse(Files.exists(dest.resolve("data/blob.bin")), "unselected entry must not be written")
                assertFalse(Files.exists(dest.resolve("a/b/c/deep.txt")))
                assertTrue(server.rangeRequestCount.get() > 0, "entry retrieval uses ranged GETs")
                val servedForEntry = server.bytesServed.get() - beforeExtract
                assertTrue(
                    servedForEntry < blob.size.toLong(),
                    "extracting the readme fetched $servedForEntry bytes — far less than the ${blob.size}-byte blob"
                )
            } finally {
                dest.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `nested entry is written under a confined tree`(): Unit = runBlocking {
        val zip = buildZip()
        TestStaticServer(mapOf("/a.zip" to zip)).use { server ->
            val client = HttpNetworkClient(HttpClient(CIO))
            val source = HttpRemoteByteSource(client, server.url("/a.zip"))
            val listing = ZipCentralDirectory.inspect(source)
            val entry = requireNotNull(listing.entryNamed("a/b/c/deep.txt"))
            val dest = Files.createTempDirectory("zip-http-nested")
            try {
                val result = RemoteZipExtractor(source).extract(listOf(entry), dest)
                assertTrue(result.allSucceeded)
                assertContentEquals(nested, Files.readAllBytes(dest.resolve("a/b/c/deep.txt")))
            } finally {
                dest.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a server that ignores ranges cannot be partially inspected`(): Unit = runBlocking {
        val zip = buildZip()
        TestStaticServer(mapOf("/a.zip" to zip), supportsRanges = false, acceptRangesHeader = "none").use { server ->
            val client = HttpNetworkClient(HttpClient(CIO))
            val source = HttpRemoteByteSource(client, server.url("/a.zip"))
            assertFalse(source.rangeSupported)
            // Without ranges the bounded directory read is impossible; that must surface, not a silent
            // full download dressed up as inspection.
            assertFailsWith<ArchiveAccessException> { ZipCentralDirectory.inspect(source) }
        }
    }
}
