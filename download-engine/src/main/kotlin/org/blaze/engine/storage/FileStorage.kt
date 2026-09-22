package org.blaze.engine.storage

import java.io.File
import java.nio.file.Path

interface FileStorage {
    fun getPartialFile(destination: Path): File

    /**
     * Sidecar that carries the per-chunk completion state of an accelerated (multi-connection)
     * download. It lives next to the partial file so a restart can resume without re-fetching
     * chunks that are already on disk.
     */
    fun getResumeStateFile(destination: Path): File =
        getPartialFile(destination).toPath().resolveSibling("${destination.fileName}.meta").toFile()

    fun exists(path: Path): Boolean
    fun size(path: Path): Long
    fun delete(path: Path)
    fun move(source: Path, target: Path)
    fun openForWrite(path: Path): FileWriteSession

    /**
     * Opens a file for concurrent writes at arbitrary offsets. Implementations must be safe to
     * use from several threads at once, because every HTTP connection worker writes its own
     * chunk into the same file.
     */
    fun openPositionedWriter(path: Path): PositionedWriter

    /**
     * Extends the file to [size] without writing anything, so out-of-order chunk writes land in
     * pre-allocated (sparse) space instead of repeatedly growing the file and fragmenting it.
     */
    fun preallocate(path: Path, size: Long)
    fun ensureDirectory(path: Path)
    fun deleteRecursively(path: Path)
}

interface FileWriteSession : AutoCloseable {
    fun seek(offset: Long)
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun setLength(length: Long)
}

/** Append-free, seek-free writes: each worker owns a disjoint slice of the file. */
interface PositionedWriter : AutoCloseable {
    fun writeAt(position: Long, buffer: ByteArray, offset: Int, length: Int)

    /** Pushes the data to the device; metadata flag also flushes the file size. */
    fun force(meta: Boolean)

    fun size(): Long

    override fun close()
}
