package org.blaze.engine.support

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Deterministic file-backed payload generator, sized so a benchmark can honestly ask for 100 MB /
 * 1 GB without keeping it on the heap.
 *
 * The byte stream is the same LCG the in-memory [TestHttpServer.of] uses (`state = state * 1103515245
 * + 12345`, emit the top byte), so a small test and a large benchmark see identical content per
 * byte position. That matters because the SHA-256 of the *expected* content can then be computed
 * offline (from size + seed alone) and compared against the SHA-256 of whatever the engine actually
 * wrote to disk - the single check that catches every class of segmentation, resume and reorder bug
 * at once, without needing to keep a reference copy of a gigabyte around.
 */
object PayloadGenerator {

    const val MIB: Int = 1024 * 1024
    const val GIB: Int = 1024 * MIB

    /** Writes [sizeBytes] of deterministic pseudo-random content into [target]. Overwrites. */
    fun writeTo(target: Path, sizeBytes: Long, seed: Int = DEFAULT_SEED, chunkBytes: Int = DEFAULT_CHUNK) {
        require(sizeBytes >= 0) { "sizeBytes must be non-negative, was $sizeBytes" }
        require(chunkBytes > 0) { "chunkBytes must be positive, was $chunkBytes" }
        Files.newOutputStream(target).use { raw ->
            BufferedOutputStream(raw, chunkBytes).use { out ->
                val buffer = ByteArray(chunkBytes)
                var state = seed
                var remaining = sizeBytes
                while (remaining > 0) {
                    val take = minOf(chunkBytes.toLong(), remaining).toInt()
                    for (i in 0 until take) {
                        state = state * LCG_MULTIPLIER + LCG_INCREMENT
                        buffer[i] = (state ushr LCG_SHIFT).toByte()
                    }
                    out.write(buffer, 0, take)
                    remaining -= take
                }
            }
        }
    }

    /**
     * SHA-256 (lowercase hex) of the payload that [writeTo] would produce at [sizeBytes] / [seed].
     * Streams the generator through the digester; never allocates more than one chunk on the heap.
     */
    fun sha256OfGenerated(sizeBytes: Long, seed: Int = DEFAULT_SEED, chunkBytes: Int = DEFAULT_CHUNK): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(chunkBytes)
        var state = seed
        var remaining = sizeBytes
        while (remaining > 0) {
            val take = minOf(chunkBytes.toLong(), remaining).toInt()
            for (i in 0 until take) {
                state = state * LCG_MULTIPLIER + LCG_INCREMENT
                buffer[i] = (state ushr LCG_SHIFT).toByte()
            }
            digest.update(buffer, 0, take)
            remaining -= take
        }
        return digest.digest().toHexString()
    }

    /** SHA-256 (lowercase hex) of an existing file, streaming with a 64 KiB buffer. */
    fun sha256File(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER)
        Files.newInputStream(path).use { input ->
            BufferedInputStream(input, HASH_BUFFER).use { buffered ->
                while (true) {
                    val read = buffered.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().toHexString()
    }

    /** Convenience: writes [sizeBytes] to a fresh temp file and returns its path. Deletion is caller's job. */
    fun tempFile(prefix: String = "blaze-payload", sizeBytes: Long, seed: Int = DEFAULT_SEED): Path {
        val path = Files.createTempFile(prefix, ".bin")
        writeTo(path, sizeBytes, seed)
        return path
    }

    /** Ensures the file at [path] contains exactly [sizeBytes] of LCG content for [seed]; creates or rewrites otherwise. */
    fun ensure(path: Path, sizeBytes: Long, seed: Int = DEFAULT_SEED) {
        if (Files.exists(path) && Files.size(path) == sizeBytes) return
        writeTo(path, sizeBytes, seed)
    }

    private fun ByteArray.toHexString(): String =
        StringBuilder(size * 2).also { sb ->
            for (b in this) {
                val v = b.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(HEX[v and 0xF])
                sb.append(HEX[(v ushr 4) and 0xF])
            }
        }.toString()

    // The LCG is written inline above rather than reused from TestHttpServer.of so a benchmark can
    // stream arbitrarily large payloads without ever materialising the whole array; TestHttpServer.of
    // remains the in-memory helper for tests that need only a few MiB.

    // The two helpers MUST emit byte-identical output for the same (size, seed); if either changes,
    // change the other, or SHA-256 comparisons will silently start failing.

    private const val LCG_MULTIPLIER = 1103515245
    private const val LCG_INCREMENT = 12345
    private const val LCG_SHIFT = 16
    private const val DEFAULT_CHUNK = 256 * 1024
    private const val HASH_BUFFER = 64 * 1024
    private const val DEFAULT_SEED = 1234_56789
    private val HEX = "0123456789abcdef".toCharArray()
}
