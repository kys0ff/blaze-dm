package org.blaze.engine.persistence

import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.BitSet

/**
 * Completion bookkeeping for a segmented download.
 *
 * Only whole chunks are ever marked done, so a crash mid-chunk simply re-downloads that chunk;
 * positioned writes make that idempotent. The validator (ETag/Last-Modified) is stored so a
 * server that swapped the file between sessions cannot be resumed into a corrupted result.
 */
@Serializable
data class HttpResumeState(
    val totalBytes: Long,
    val chunkSize: Long,
    val validator: String?,
    val completedChunks: String
) {
    fun toBitSet(): BitSet = BitSet.valueOf(Base64.getDecoder().decode(completedChunks))

    companion object {
        fun of(totalBytes: Long, chunkSize: Long, validator: String?, completed: BitSet): HttpResumeState =
            HttpResumeState(totalBytes, chunkSize, validator, Base64.getEncoder().encodeToString(completed.toByteArray()))
    }
}
