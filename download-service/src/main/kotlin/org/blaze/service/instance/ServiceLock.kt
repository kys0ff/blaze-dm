package org.blaze.service.instance

import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
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
 *
 * The JDK layers a per-JVM [java.nio.channels.FileLockTable] over POSIX locks: a second
 * [FileChannel.tryLock] of the same file from the *same JVM* throws [OverlappingFileLockException]
 * rather than quietly succeeding. [probe] reports that as its own state, so a read-only "is the API
 * taken?" question can never be misread as "some other service owns it" inside the process that owns
 * it itself - and never releases the real owner by closing the probing channel.
 *
 * No `DELETE_ON_CLOSE` (measured bug): with it the JDK may unlink the *path* while the holder keeps
 * locking the orphaned inode, so the next process creates a fresh file at the path, locks it and
 * "wins" a directory somebody else owns. The empty lock file simply persists between runs - harmless,
 * and the only race-free choice.
 */
class ServiceLock(private val lockFile: Path) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ServiceLock::class.java)
    private var channel: FileChannel? = null
    private var lock: java.nio.channels.FileLock? = null

    /** What an exclusive-lock attempt against [lockFile] came back as. */
    enum class LockProbe {
        /** Nobody holds it - a service may take it. */
        FREE,

        /** Another live process holds it. */
        HELD_BY_OTHER,

        /** This very JVM holds it (the JDK flags it as an overlapping lock, not as a refusal). */
        HELD_BY_US,
    }

    /** True when this instance owns the lock. */
    val isOwner: Boolean get() = lock != null

    /**
     * Attempts to take the lock. Returns true on success - including when the same JVM already holds
     * it, which is ownership, never a competing service. False only when another process holds it.
     */
    fun acquire(): Boolean {
        if (lock != null) return true
        return when (probe(lockFile)) {
            LockProbe.FREE -> {
                Files.createDirectories(lockFile.toAbsolutePath().parent)
                val ch = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
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
            }

            LockProbe.HELD_BY_US -> true

            LockProbe.HELD_BY_OTHER -> {
                logger.info("The service lock at {} is held by another live process.", lockFile)
                false
            }
        }
    }

    override fun close() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ServiceLock::class.java)

        /**
         * Exclusive-lock attempt answered as a three-state [LockProbe], without keeping the lock.
         * Unlike [acquire] this never creates the file: a probe of a directory where the API never
         * ran must not leave a zero-byte lock behind.
         */
        fun probe(lockFile: Path): LockProbe {
            if (!Files.exists(lockFile)) return LockProbe.FREE
            val ch = try {
                FileChannel.open(lockFile, StandardOpenOption.WRITE)
            } catch (e: Exception) {
                logger.warn("Could not inspect the service lock at {}", lockFile, e)
                return LockProbe.HELD_BY_OTHER
            }
            try {
                val acquired = try {
                    ch.tryLock()
                } catch (e: OverlappingFileLockException) {
                    return LockProbe.HELD_BY_US
                }
                if (acquired == null) return LockProbe.HELD_BY_OTHER
                runCatching { acquired.release() }
                return LockProbe.FREE
            } finally {
                runCatching { ch.close() }
            }
        }

        /** True when the endpoint is free for this process to take: no file, or held by nobody live. */
        fun isFree(lockFile: Path): Boolean = probe(lockFile) == LockProbe.FREE
    }
}
