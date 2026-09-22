package org.blaze.engine.network

import bt.dht.DHTConfig
import bt.dht.DHTModule
import bt.peerexchange.PeerExchangeModule
import bt.runtime.BtRuntime
import bt.runtime.Config
import bt.tracker.http.HttpTrackerModule
import org.slf4j.LoggerFactory
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Owns the process-wide `BtRuntime` that every torrent download runs on.
 *
 * Building a runtime is not cheap: it binds an acceptor port, starts a DHT node and bootstraps it
 * against the routers, and spins up worker executors. Creating one per download (and one *more*
 * per automatic retry of the same download) multiplied that cost, made every retry pay several
 * seconds of swarm re-discovery, and left several DHT nodes from one process competing on the same
 * network. `bt` is designed for one runtime hosting many clients, so that is what we do now.
 *
 * The runtime is recreated when the settings that shaped it change, and after the last client has
 * been using nothing from it for [idleGrace] - so an idle app stops holding a port and a DHT node.
 */
class TorrentRuntimeProvider(private val idleGrace: Duration = IDLE_GRACE) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(TorrentRuntimeProvider::class.java)

    private class Lease(val runtime: BtRuntime, val fingerprint: String) {
        var refs = 0
        var shuttingDown = false
    }

    private val lock = Any()

    private var lease: Lease? = null
    private var idleTask: ScheduledFuture<*>? = null

    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "bt-runtime-idle").apply { isDaemon = true }
        }
    }

    /**
     * Returns a runtime configured by [configure]; [fingerprint] must change whenever the
     * configuration inputs change so a stale runtime is never reused for different settings.
     *
     * A runtime that still has clients on it is never torn down, even when the fingerprint has
     * changed: shutting it down would abort whatever torrents happen to be running, and the
     * settings that shape it (the peer budget) are nowhere near urgent enough to pay for that.
     * The new configuration is used by the next acquire once the last client has released.
     */
    fun acquire(fingerprint: String, configure: (Config) -> Unit): BtRuntime = synchronized(lock) {
        cancelIdleShutdown()

        val existing = lease
        if (existing != null && !existing.shuttingDown) {
            if (existing.refs > 0 || existing.fingerprint == fingerprint) {
                existing.refs++
                return@synchronized existing.runtime
            }
            // Idle and shaped by different settings: replace it while nobody is using it.
            shutdownQuietly(existing)
            lease = null
        }

        val created = runCatching { buildRuntime(configure) }
            .onFailure { logger.error("Failed to build the torrent runtime", it) }
            .getOrThrow()
        lease = Lease(created, fingerprint).apply { refs = 1 }
        created
    }

    private fun cancelIdleShutdown() {
        idleTask?.cancel(false)
        idleTask = null
    }

    fun release() = synchronized(lock) {
        val current = lease ?: return@synchronized
        current.refs--
        if (current.refs > 0) return@synchronized
        idleTask = scheduler.schedule({
            synchronized(lock) {
                val now = lease
                if (now != null && now.refs == 0 && now === current) {
                    shutdownQuietly(now)
                    lease = null
                }
            }
        }, idleGrace.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        synchronized(lock) {
            idleTask?.cancel(false)
            lease?.let { shutdownQuietly(it) }
            lease = null
        }
        runCatching { scheduler.shutdownNow() }
    }

    private fun shutdownQuietly(target: Lease) {
        target.shuttingDown = true
        runCatching { target.runtime.shutdown() }
            .onFailure { logger.warn("Torrent runtime shutdown failed", it) }
    }

    private fun buildRuntime(configure: (Config) -> Unit): BtRuntime {
        val config = Config().apply(configure)
        return BtRuntime.builder(config)
            .module(DHTModule(object : DHTConfig() {
                override fun shouldUseRouterBootstrap(): Boolean = true
            }))
            .module(HttpTrackerModule())
            .module(PeerExchangeModule())
            .disableAutomaticShutdown()
            .build()
    }

    companion object {
        val IDLE_GRACE: Duration = 60.seconds

        /** Shared by every client that does not bring its own provider (i.e. the whole app). */
        val GLOBAL: TorrentRuntimeProvider by lazy { TorrentRuntimeProvider() }
    }
}

/**
 * Applies the tuned `bt` defaults that decide how aggressively a client uses its peers.
 *
 * The library's stock values are conservative single-torrent settings: it lets only 10 peers work
 * on a torrent at a time and keeps 3 pieces in flight, which caps throughput long before the
 * network does, and it gives a piece assignment only five seconds before reassigning it - churn
 * that also bans slow-but-usable peers on high-latency links.
 */
internal fun applyPerformanceConfig(
    config: Config,
    maxPeerConnections: Int,
    bindAddress: InetAddress
) {
    config.acceptorAddress = bindAddress
    config.maxPeerConnections = maxPeerConnections
    config.maxPeerConnectionsPerTorrent = maxPeerConnections

    // Peers allowed to serve pieces at the same time (library default: 10).
    config.maxConcurrentlyActivePeerConnectionsPerTorrent = maxPeerConnections.coerceAtMost(60).coerceAtLeast(10)

    // Pieces in flight overall (library default: 3); scaled up so fast links stay saturated
    // even when a piece is large.
    config.maxSimultaneouslyAssignedPieces = (maxPeerConnections / 8).coerceIn(4, 20)

    // Request pipelining and block size: bigger blocks mean fewer round trips per megabyte.
    config.transferBlockSize = 32 * 1024
    config.maxTransferBlockSize = 128 * 1024
    config.maxOutstandingRequests = 250

    // Backpressure on the write path: the library default is an unbounded queue, which on a disk
    // slower than the network turns into unbounded memory growth instead of throttling the peers.
    config.maxIOQueueSize = 128

    // Verification parallelism, bounded so several torrents plus the UI still fit on the CPU.
    config.numOfHashingThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    // Slow peers: release a stalled assignment, but do not ban the peer for a minute - in a small
    // swarm that can remove the only source of the remaining pieces.
    config.maxPieceReceivingTime = 60.seconds.toJavaDuration()
    config.timeoutedAssignmentPeerBanDuration = 15.seconds.toJavaDuration()

    // Interface changes and brief disconnects make known peers unreachable; do not carry that
    // grudge for half an hour.
    config.unreachablePeerBanDuration = 5.minutes.toJavaDuration()
    config.peerConnectionTimeout = 15.seconds.toJavaDuration()
    config.peerConnectionRetryCount = 3
    config.peerConnectionRetryInterval = 5.seconds.toJavaDuration()
    config.peerConnectionInactivityThreshold = 90.seconds.toJavaDuration()

    config.trackerTimeout = 20.seconds.toJavaDuration()
    config.trackerQueryInterval = 30.seconds.toJavaDuration()
    config.numberOfPeersToRequestFromTracker = 80
}

/** Exposes the peer/assignment tuning without leaking the `bt` Config type into callers. */
internal fun torrentRuntimeFingerprint(maxPeerConnections: Int): String = "peers=$maxPeerConnections"

/**
 * Address the acceptor binds to. Ask the OS which local address routes to the internet first (a
 * UDP "connect" sends nothing), then fall back to the first plausible physical interface, so the
 * client never ends up bound to a docker/veth address where no peer can reach it.
 */
internal fun resolveBindAddress(): InetAddress {
    runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName("8.8.8.8"), 53)
            val local = socket.localAddress
            if (local.isUsableIpv4()) return local
        }
    }
    val virtualPrefixes = listOf("docker", "veth", "br-", "virbr", "vboxnet", "vmnet", "zt", "lo")
    runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { iface -> virtualPrefixes.any { iface.name.startsWith(it) } }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it.isUsableIpv4() }
            ?.let { return it }
    }
    return InetAddress.getByName("0.0.0.0")
}

private fun InetAddress.isUsableIpv4(): Boolean =
    this is Inet4Address && !isLoopbackAddress && !isAnyLocalAddress && !isLinkLocalAddress
