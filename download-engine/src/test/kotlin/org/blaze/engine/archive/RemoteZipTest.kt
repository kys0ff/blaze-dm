package org.blaze.engine.archive

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/**
 * Deterministic coverage of remote-ZIP inspection and selective extraction against an in-memory byte
 * source, so the range arithmetic, ZIP64-free parsing, path confinement and compression handling are
 * all proven without a network or a public file host.
 */
class RemoteZipTest {

    /** A [RemoteByteSource] over a fixed array; ranges always succeed unless told otherwise. */
    private class ByteArraySource(
        private val data: ByteArray,
        override val rangeSupported: Boolean = true,
        private val reportSize: Boolean = true,
    ) : RemoteByteSource {
        val reads = mutableListOf<Pair<Long, Long>>()
        override suspend fun size(): Long? = if (reportSize) data.size.toLong() else null
        override suspend fun read(start: Long, length: Long): ByteArray {
            reads += start to length
            require(start >= 0 && length >= 0 && start + length <= data.size) {
                "read ($start,$length) out of bounds of ${data.size}"
            }
            return data.copyOfRange(start.toInt(), (start + length).toInt())
        }
    }

    private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                if (name.endsWith("/")) {
                    zip.putNextEntry(entry)
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(entry)
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `inspection lists entries with names sizes and directory flags`(): Unit = runBlocking {
        val zip = buildZip(
            "docs/" to "",
            "docs/readme.txt" to "hello world, this is a readable file",
            "data/nested/values.csv" to "a,b,c\n1,2,3",
        )
        val listing = ZipCentralDirectory.inspect(ByteArraySource(zip))
        val names = listing.entries.map { it.name }
        assertTrue("docs/" in names)
        assertTrue("docs/readme.txt" in names)
        assertTrue("data/nested/values.csv" in names)
        val dir = listing.entryNamed("docs/")
        assertNotNull(dir)
        assertTrue(dir.isDirectory)
        val readme = listing.entryNamed("docs/readme.txt")!!
        assertFalse(readme.isDirectory)
        assertTrue(readme.uncompressedSize > 0)
        assertTrue(listing.selectiveSupported)
    }

    @Test
    fun `selective extraction writes only the chosen entry and nothing else`(): Unit = runBlocking {
        val content = "the quick brown fox jumps over the lazy dog ".repeat(50)
        val zip = buildZip(
            "wanted.txt" to content,
            "unwanted.txt" to "SECRET DATA that must never be fetched or written",
        )
        val source = ByteArraySource(zip)
        val listing = ZipCentralDirectory.inspect(source)
        val wanted = listing.entryNamed("wanted.txt")!!

        val dir = Files.createTempDirectory("blaze-zip-select")
        try {
            val result = RemoteZipExtractor(source).extract(listOf(wanted), dir)
            assertTrue(result.allSucceeded, "failures: ${result.failures}")
            assertEquals(content, Files.readString(dir.resolve("wanted.txt")))
            assertFalse(Files.exists(dir.resolve("unwanted.txt")), "an unselected entry was extracted")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `extraction recreates nested folders`(): Unit = runBlocking {
        val zip = buildZip("a/b/c/deep.txt" to "deep content here")
        val source = ByteArraySource(zip)
        val listing = ZipCentralDirectory.inspect(source)
        val entry = listing.entryNamed("a/b/c/deep.txt")!!
        val dir = Files.createTempDirectory("blaze-zip-nested")
        try {
            val result = RemoteZipExtractor(source).extract(listOf(entry), dir)
            assertTrue(result.allSucceeded, "failures: ${result.failures}")
            assertEquals("deep content here", Files.readString(dir.resolve("a/b/c/deep.txt")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a server without range support is reported as not selectively retrievable`(): Unit = runBlocking {
        val zip = buildZip("x.txt" to "data")
        val listing = ZipCentralDirectory.inspect(ByteArraySource(zip, rangeSupported = false))
        assertFalse(listing.selectiveSupported)
        assertNotNull(listing.reasonIfUnsupported)
    }

    @Test
    fun `an unknown-length source cannot be inspected for a central directory`(): Unit = runBlocking {
        val zip = buildZip("x.txt" to "data")
        assertFailsWith<ArchiveAccessException> {
            ZipCentralDirectory.inspect(ByteArraySource(zip, reportSize = false))
        }
    }

    @Test
    fun `html that is not a zip is rejected, not mis-read`(): Unit = runBlocking {
        val body = "<!DOCTYPE html><html><body>404 Not Found</body></html>".toByteArray()
        assertFailsWith<ArchiveAccessException> {
            ZipCentralDirectory.inspect(ByteArraySource(body))
        }
    }

    @Test
    fun `unsafe entry paths are confined to the destination`() {
        val extractor = RemoteZipExtractor(ByteArraySource(ByteArray(1)))
        val root = java.nio.file.Path.of("/tmp/dest")
        assertNull(extractor.safeResolve(root, "../escape.txt"))
        assertNull(extractor.safeResolve(root, "/absolute.txt"))
        assertNull(extractor.safeResolve(root, "C:/windows/system32/evil.dll"))
        assertNull(extractor.safeResolve(root, "a/../../b.txt"))
        assertNull(extractor.safeResolve(root, ""))
        assertNull(extractor.safeResolve(root, "with\u0000nul.txt"))
        assertNotNull(extractor.safeResolve(root, "good/dir/file.txt"))
    }
}
