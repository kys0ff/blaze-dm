package org.blaze.service.instance

import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Guarantees at most one downloader service runs against a given data directory.
 *
 * A second GUI or `blaze serve` should not spin up an independent engine racing over the same
 * `downloads.json` and partial files. This takes an OS-level exclusive [FileChannel.lock] on a lock file:
 * the kernel drops it automatically if the process dies, so a crash never wedges a stale lock. When the
 * lock is already held, [acquire] returns false and the caller should connect to the running service
 * instead of starting a new one.
 */
class ServiceLock(private val lockFile: Path) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ServiceLock::class.java)
    private var channel: FileChannel? = null
    private var lock: java.nio.channels.FileLock? = null

    /** True when this instance owns the lock. */
    val isOwner: Boolean get() = lock != null

    /**
     * Attempts to take the lock. Returns true on success; false when another live process holds it.
     */
    fun acquire(): Boolean {
        if (lock != null) return true
        return try {
            Files.createDirectories(lockFile.toAbsolutePath().parent)
            val ch = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.DELETE_ON_CLOSE,
            )
            val acquired = ch.tryLock()
            if (acquired == null) {
                ch.close()
                false
            } else {
                channel = ch
                lock = acquired
                true
            }
        } catch (e: Exception) {
            logger.warn("Could not acquire the service lock at {}", lockFile, e)
            false
        }
    }

    override fun close() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }
}
