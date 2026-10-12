package org.blaze.osintegration.singleinstance

import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
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
 * POSIX record locks are per-*process*, but the JDK layers its own [java.nio.channels.FileLockTable]
 * on top: a second [FileChannel.tryLock] of the same file from the *same JVM* does not succeed, it
 * throws [OverlappingFileLockException]. A probe that cannot tell the two apart reads "our own process
 * already owns this" as "some other instance does" - which is exactly how a healthy run logged a
 * phantom single-instance failure at every startup. [probe] therefore reports the three states
 * separately, and callers like [InstanceArtifacts] refuse to sweep a channel this JVM itself holds.
 * (Tests simulating *another* instance still must fork a real JVM - see SingleInstanceForwardingTest.)
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

    /** What an exclusive-lock attempt against [lockFile] came back as. */
    enum class LockProbe {
        /** Nobody holds it - an owner may take it. */
        FREE,

        /** Another live process holds it. */
        HELD_BY_OTHER,

        /** This very JVM holds it (the JDK flags it as an overlapping lock, not as a refusal). */
        HELD_BY_US,
    }

    val isOwner: Boolean get() = lock != null

    /**
     * Attempts to take the lock. Returns true when this instance owns it - including the probe case
     * where the same JVM already holds it, which is ownership, never a foreign instance.
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
                logger.info("The single-instance lock at {} is held by another live process.", lockFile)
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
        private val logger = LoggerFactory.getLogger(InstanceLock::class.java)

        /**
         * Exclusive-lock attempt answered as a three-state [LockProbe], without keeping the lock: the
         * probing channel is always closed and any lock it took is released. [LockProbe.HELD_BY_OTHER]
         * and [LockProbe.HELD_BY_US] both mean "do not touch what is in use", differing only in who is
         * using it - and neither ever disturbs the real holder.
         */
        internal fun probe(lockFile: Path): LockProbe {
            if (!Files.exists(lockFile)) return LockProbe.FREE
            val ch = try {
                FileChannel.open(lockFile, StandardOpenOption.WRITE)
            } catch (e: Exception) {
                logger.warn("Could not inspect the single-instance lock at {}", lockFile, e)
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
    }
}
