package org.blaze.osintegration.singleinstance

import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Kernel-held exclusive lock marking "this process is the one GUI for the data directory".
 *
 * Same mechanism the download service uses for its API endpoint: the OS drops the lock when the process
 * dies, so a crashed instance never wedges a stale lock, and a second launcher learns - reliably and
 * without any polling protocol - that it must forward its request instead of opening a second window.
 *
 * POSIX record locks are per-*process*: a second acquire of the same file from the same JVM succeeds
 * silently. That is sound here (exactly one coordinator exists per application process, created once
 * in `main`), but tests simulating another instance must fork a real JVM - see SingleInstanceForwardingTest.
 *
 * Deliberately NO `DELETE_ON_CLOSE`: measured on this platform, a delete-on-close channel lets the JDK
 * unlink the *path* while the process keeps locking the orphaned inode - the next process then creates
 * a fresh file, locks it, and "wins" a directory somebody else owns. A plain empty lock file that
 * outlives runs is harmless (its bytes never matter; the kernel lock is what counts) and race-free.
 */
class InstanceLock(private val lockFile: Path) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(InstanceLock::class.java)
    private var channel: FileChannel? = null
    private var lock: java.nio.channels.FileLock? = null

    val isOwner: Boolean get() = lock != null

    fun acquire(): Boolean {
        if (lock != null) return true
        return try {
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
        } catch (e: Exception) {
            logger.warn("Could not take the single-instance lock at {}", lockFile, e)
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
