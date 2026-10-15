package org.blaze.engine.support

import bt.metainfo.TorrentId
import bt.module.ServiceModule
import bt.net.Peer
import bt.peer.PeerSource
import bt.peer.PeerSourceFactory
import bt.runtime.BtRuntime
import bt.runtime.Config
import com.google.inject.Module
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration

/**
 * Local swarm harness that actually transfers, for tests and benchmarks that need a real second peer.
 *
 * The earlier harness put one seeder and one leecher inside a single `BtRuntime` and tried to meet
 * them over LAN multicast. That cannot work structurally, not just flakily: one runtime owns exactly
 * one acceptor (`ServiceModule.provideSocketChannelConnectionAcceptor` binds
 * `config.acceptorAddress:config.acceptorPort`) and exactly one `localPeer` (`PeerRegistry.java:81`).
 * A peer discovered from inside the same process therefore advertises the same address:port as the
 * registry's own local peer, and `PeerRegistry.addPeer` drops it as `isLocal` (`PeerRegistry.java:222`).
 * Whether multicast works in a sandbox is beside the point once the discovery answer is filtered.
 *
 * So: one runtime per role, each with its own acceptor port on loopback, and the leecher learns the
 * seeder's socket from an explicit static peer source — no tracker, no DHT, no multicast. The static
 * source re-offers the peers on every discovery poll (250 ms here), so the leecher keeps retrying
 * until the seeder's acceptor is actually up; the connection layer deduplicates by peer.
 */
object SwarmHarness {

    fun loopback(): InetAddress = InetAddress.getLoopbackAddress()

    /**
     * Reserves a currently-free TCP port. The socket is closed before returning, so there is a tiny
     * race until whoever binds it next does; acceptable on a test machine, and the swarm test fails
     * loudly (no peers reachable → transfer timeout) rather than silently if the race is lost.
     */
    fun reserveFreePort(): Int = ServerSocket(0).use { it.localPort }

    /** A seeding runtime: plain bt-core, bound to `127.0.0.1:[port]`, nothing loaded beyond defaults. */
    fun seederRuntime(acceptorPort: Int, configure: (Config) -> Unit = {}): BtRuntime =
        runtime(acceptorPort, emptyList(), configure)

    /** A leeching runtime that is told exactly which peers exist — the seeder's loopback socket. */
    fun leecherRuntime(acceptorPort: Int, knownPeers: List<Peer>, configure: (Config) -> Unit = {}): BtRuntime =
        runtime(acceptorPort, knownPeers, configure)

    private fun runtime(port: Int, knownPeers: List<Peer>, configure: (Config) -> Unit): BtRuntime {
        val config = Config().apply {
            acceptorAddress = loopback()
            acceptorPort = port
            // Query peer sources fast so the leecher meets the seeder within the first second.
            peerDiscoveryInterval = Duration.ofMillis(250)
        }
        // Tests exercise runtime-shaped features (like the fork's fast resume) through the same
        // injection point production uses: `applyPerformanceConfig` on the real `Config`.
        configure(config)
        val builder = BtRuntime.builder(config).disableAutomaticShutdown()
        return if (knownPeers.isEmpty()) {
            builder.build()
        } else {
            builder.module(Module { binder ->
                ServiceModule.extend(binder).addPeerSourceFactory(StaticPeerSourceFactory(knownPeers))
            }).build()
        }
    }

    /**
     * Serves the same fixed peer list on every discovery cycle. `PeerRegistry.queryPeerSource`
     * mutates the returned collection, so each call hands out a fresh copy; repeated
     * `PeerDiscoveredEvent`s for an already-connected peer are deduplicated downstream.
     *
     * The list is deliberately NOT named `peers`: inside `object : PeerSource`, a bare `peers`
     * reference resolves to the receiver's own `getPeers()` synthetic property and recurses
     * infinitely — a `StackOverflowError` that silently kills bt's scheduled peer-collector thread.
     */
    private class StaticPeerSourceFactory(fixedPeers: List<Peer>) : PeerSourceFactory {
        private val knownPeers: List<Peer> = ArrayList(fixedPeers)

        override fun getPeerSource(torrentId: TorrentId): PeerSource = object : PeerSource {
            override fun update(): Boolean = true
            override fun getPeers(): Collection<Peer> = ArrayList(knownPeers)
        }
    }
}
