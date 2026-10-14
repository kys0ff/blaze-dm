package org.blaze.engine.support

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [PayloadGenerator]: the streaming generator and the in-memory [TestHttpServer.of]
 * helper must produce byte-identical content, and the SHA-256 of that content must be predictable
 * from size + seed alone (so a benchmark can verify a 100 MiB download against a hash computed on
 * the fly, without ever storing a reference copy).
 */
class PayloadGeneratorTest {

    @Test
    fun `generated file matches the in-memory LCG byte-for-byte`() {
        val size = 1_000_003 // deliberately non-multiple of the 256 KiB chunk
        val seed = 42
        val path = Files.createTempFile("blaze-gen-", ".bin")
        try {
            PayloadGenerator.writeTo(path, size.toLong(), seed)
            val fileBytes = Files.readAllBytes(path)
            val memory = TestHttpServer.of(size, seed)
            assertEquals(size, fileBytes.size)
            assertTrue(fileBytes.contentEquals(memory), "file bytes diverge from TestHttpServer.of")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `sha256 of generated stream equals sha256 of the file on disk`() {
        val size = 4L * 1024 * 1024 + 7
        val seed = 99
        val path = Files.createTempFile("blaze-sha-", ".bin")
        try {
            PayloadGenerator.writeTo(path, size, seed)
            val expected = PayloadGenerator.sha256OfGenerated(size, seed)
            val actual = PayloadGenerator.sha256File(path)
            assertEquals(expected, actual, "SHA-256 of the streamed generator must equal SHA-256 of the written file")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `ensure is a no-op when the file already has the target size`() {
        val path = Files.createTempFile("blaze-ensure-", ".bin")
        try {
            PayloadGenerator.ensure(path, 1024, seed = 1)
            val firstHash = PayloadGenerator.sha256File(path)
            PayloadGenerator.ensure(path, 1024, seed = 1) // must skip regeneration
            assertEquals(firstHash, PayloadGenerator.sha256File(path))
            PayloadGenerator.ensure(path, 2048, seed = 1) // different size: must rewrite
            assertEquals(2048L, Files.size(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
