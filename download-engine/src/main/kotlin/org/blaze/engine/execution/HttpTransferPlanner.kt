package org.blaze.engine.execution

/**
 * Turns "here is a file and N connections" into a work breakdown.
 *
 * Chunk count matters more than chunk size for throughput: the units have to be small enough
 * that a slow connection cannot dominate the finish, but large enough that per-request overhead
 * (a new GET + TLS record setup on a pooled connection) stays negligible.
 */
object HttpTransferPlanner {
    const val MIN_CHUNK = 1L * 1024 * 1024
    const val MAX_CHUNK = 64L * 1024 * 1024

    /** Upper bound on tracked work units, so the resume sidecar stays tiny on huge archives. */
    const val MAX_CHUNKS = 20_000

    data class Plan(val chunkSize: Long, val chunkCount: Int) {
        fun startOf(chunk: Int): Long = chunk * chunkSize
        fun endOf(chunk: Int, totalBytes: Long): Long = minOf(startOf(chunk) + chunkSize, totalBytes) - 1
        fun sizeOf(chunk: Int, totalBytes: Long): Long = endOf(chunk, totalBytes) - startOf(chunk) + 1
    }

    fun plan(totalBytes: Long, connections: Int, preferredChunkBytes: Long): Plan {
        if (totalBytes <= 0) return Plan(MIN_CHUNK, 0)
        if (connections <= 1) return Plan(totalBytes, 1)

        var chunk = preferredChunkBytes.coerceIn(MIN_CHUNK, MAX_CHUNK)

        // Enough units for every connection to stay busy and for late finishers to be picked up.
        val smallestAcceptable = totalBytes / (connections * 8L).coerceAtLeast(2L)
        chunk = minOf(chunk, maxOf(MIN_CHUNK, smallestAcceptable))

        // Never let a 4 KiB chunk size turn a 100 GiB file into 26M tracked units. The division is
        // rounded up, otherwise the resulting count can land one chunk over the cap.
        chunk = maxOf(chunk, (totalBytes + MAX_CHUNKS - 1) / MAX_CHUNKS).coerceIn(MIN_CHUNK, MAX_CHUNK)

        val count = ((totalBytes + chunk - 1) / chunk).toInt()
        return Plan(chunk, count.coerceAtLeast(1))
    }
}
