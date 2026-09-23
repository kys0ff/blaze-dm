package org.blaze.osintegration.singleinstance

import org.blaze.download.api.ServicePaths
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.osintegration.ExternalOpenRequest
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** What the launcher should do once the single-instance decision has been made. */
sealed interface SingleInstanceOutcome {
    /** This process is the one instance: keep booting the GUI; the inbox receives later forwards. */
    data class Owner(val coordinator: SingleInstanceCoordinator) : SingleInstanceOutcome

    /** An owner was live; the open requests were delivered to it, so this process must exit. */
    data object Forwarded : SingleInstanceOutcome

    /**
     * An owner holds the lock but its channel never answered (it may be mid-shutdown). The caller
     * should still start a GUI with its requests kept in the inbox - degraded, but never dropping them.
     */
    data class Unreachable(val requests: List<ExternalOpenRequest>) : SingleInstanceOutcome
}

/**
 * Decides - exactly once, at process start - whether this process *is* the app instance or merely a
 * launcher delivering a request to the instance that already runs.
 *
 * Ownership is the kernel-held [InstanceLock] over the data directory; warm open requests cross the
 * authenticated [InstanceClient]/[InstanceServer] channel. The retry loop covers the narrow race where
 * the owner is still binding its listener (or just died) when a browser launches a second process -
 * the request must survive it, which is why forwarding is attempted before anyone gives up.
 */
class SingleInstanceCoordinator(
    storageDir: Path,
    private val inbox: ExternalOpenInbox,
    private val parser: ExternalOpenParser = ExternalOpenParser(),
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(SingleInstanceCoordinator::class.java)
    private val lock = InstanceLock(ServicePaths.instanceLockFileFor(storageDir))
    private val tokenStore = InstanceTokenStore(ServicePaths.instanceTokenFileFor(storageDir))
    private val socketFile = ServicePaths.instanceSocketFileFor(storageDir)
    private val tcpPortFile = ServicePaths.instanceTcpFileFor(storageDir)
    private var server: InstanceServer? = null

    /** Tries to own the directory and start the listener; releases the lock again on failure. */
    fun tryStartAsOwner(): Boolean {
        if (!lock.acquire()) return false
        val candidate = InstanceServer(socketFile, tcpPortFile, tokenStore, parser, inbox)
        if (!candidate.start()) {
            logger.warn("Took the instance lock but could not bind a listener; running without forwarding.")
            // Ownership still matters: it stops a second GUI from opening while this one runs.
        }
        server = candidate
        return true
    }

    /** True when the running instance acknowledged the requests (they are now its inbox's problem). */
    fun forward(requests: List<ExternalOpenRequest>): Boolean =
        InstanceClient.send(socketFile, tcpPortFile, InstanceProtocol.encode(tokenStore.readPublished() ?: return false, requests))

    override fun close() {
        runCatching { server?.close() }
        server = null
        runCatching { lock.close() }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SingleInstanceCoordinator::class.java)
        private const val ATTEMPTS = 5
        private const val RETRY_DELAY_MILLIS = 400L

        /**
         * The one call the launcher makes. Order matters: become owner if possible; else forward and
         * exit; else (owner still binding / half-dead) retry briefly before degrading. On every
         * non-forwarded outcome the requests land in [inbox] exactly once, so the caller never
         * submits them again.
         */
        fun start(
            storageDir: Path,
            inbox: ExternalOpenInbox,
            requests: List<ExternalOpenRequest>,
        ): SingleInstanceOutcome {
            val coordinator = SingleInstanceCoordinator(storageDir, inbox)
            var lastFailure: Boolean? = null
            repeat(ATTEMPTS) { attempt ->
                if (coordinator.tryStartAsOwner()) {
                    inbox.submit(requests) // our own cold-start argv; no one else will deliver them
                    return SingleInstanceOutcome.Owner(coordinator)
                }
                lastFailure = coordinator.forward(requests)
                if (lastFailure == true) {
                    coordinator.close()
                    return SingleInstanceOutcome.Forwarded
                }
                if (attempt + 1 < ATTEMPTS) {
                    runCatching { Thread.sleep(RETRY_DELAY_MILLIS) }
                }
            }
            logger.warn(
                "Another Blaze instance holds the data directory but {} (requests: {}); starting anyway.",
                if (lastFailure == false) "did not answer the hand-off" else "is still taking the lock",
                requests.size,
            )
            inbox.submit(requests)
            return SingleInstanceOutcome.Unreachable(requests)
        }
    }
}
