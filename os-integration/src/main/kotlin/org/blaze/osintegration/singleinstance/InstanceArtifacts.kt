package org.blaze.osintegration.singleinstance

import org.blaze.download.api.ServicePaths
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The files the single-instance channel leaves behind in the data directory: the ownership lock, the
 * socket (or loopback port file) the owner listens on, and the shared secret.
 *
 * A clean shutdown removes them ([InstanceServer.close]), but a debug session almost never shuts down
 * cleanly - the IDE's Stop button is SIGTERM, and `kill -9` is nothing at all - so a stale
 * `instance.sock` is a routine sight in a working directory. They are harmless to the *next* owner
 * (which unlinks the socket before binding), but they must go when the app itself is removed, and they
 * must never be touched while a live instance is using them: deleting a running owner's socket blinds
 * every launcher that follows. That is what [isOwned] is for.
 */
object InstanceArtifacts {
    private val logger = LoggerFactory.getLogger(InstanceArtifacts::class.java)

    /** The IPC plumbing itself: lock, socket and (fallback) port file. */
    fun channelFiles(storageDir: Path): List<Path> = listOf(
        ServicePaths.instanceLockFileFor(storageDir),
        ServicePaths.instanceSocketFileFor(storageDir),
        ServicePaths.instanceTcpFileFor(storageDir),
    )

    /**
     * Everything the channel owns, including the `0600` shared secret.
     *
     * The secret is appended with [buildList], never `channelFiles(dir) + token`: [Path] is itself
     * `Iterable<Path>`, so a `+` on a list of paths silently prefers the iterable overload and appends
     * the token's *name elements* (`tmp`, `blaze-123`, `instance-token`) instead of the file.
     */
    fun allFiles(storageDir: Path): List<Path> = buildList {
        addAll(channelFiles(storageDir))
        add(ServicePaths.instanceTokenFileFor(storageDir))
    }

    /**
     * True while the ownership lock is live *somewhere*: held by this process (the JDK reports a
     * same-JVM re-lock as an overlapping lock, not as a refusal) or by another instance. Either way
     * the channel is in use and none of it may be deleted.
     */
    fun isOwned(storageDir: Path): Boolean {
        val lockFile = ServicePaths.instanceLockFileFor(storageDir)
        // A lock file that was never created cannot be held. Probing it anyway would be wrong twice
        // over: a lock-taking probe creates the file it locks, so a read-only question would leave
        // a zero-byte file behind, and the sweep that then follows would report deleting a file it had
        // just invented - making an already-clean install look like it needed cleaning on every run.
        // ([InstanceLock.probe] answers without creating or claiming anything.)
        if (!Files.exists(lockFile)) return false
        return InstanceLock.probe(lockFile) != InstanceLock.LockProbe.FREE
    }

    /**
     * Deletes the channel's files, returning what actually went. Refuses (and says so) while a live
     * instance owns the directory; [includeToken] additionally drops the shared secret, which only an
     * uninstall-style cleanup should.
     */
    fun clear(storageDir: Path, includeToken: Boolean = false): List<Path> {
        if (isOwned(storageDir)) {
            logger.info("Leaving the single-instance files in {} alone: a running instance owns them.", storageDir)
            return emptyList()
        }
        val candidates = if (includeToken) allFiles(storageDir) else channelFiles(storageDir)
        return candidates.filter { path ->
            runCatching { Files.deleteIfExists(path) }
                .onFailure { logger.warn("Could not remove the leftover instance file {}", path, it) }
                .getOrDefault(false)
        }
    }
}
