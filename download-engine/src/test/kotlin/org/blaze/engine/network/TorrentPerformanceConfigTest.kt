package org.blaze.engine.network

import bt.runtime.Config
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `bt` library's stock configuration is written for a modest single-torrent client, and every
 * value below was chosen to remove a specific throughput or reliability ceiling. They are asserted
 * here so a future "just upgrade the defaults" change has to argue with these reasons.
 */
class TorrentPerformanceConfigTest {

    private fun configured(peers: Int): Config = Config().also {
        applyPerformanceConfig(it, peers, InetAddress.getByName("127.0.0.1"))
    }

    @Test
    fun `more peers are allowed to serve pieces at once`() {
        // Library default is 10 active peers and 3 assigned pieces, which caps a fast link early.
        val config = configured(peers = 200)

        assertEquals(60, config.maxConcurrentlyActivePeerConnectionsPerTorrent)
        assertEquals(20, config.maxSimultaneouslyAssignedPieces)
        assertEquals(200, config.maxPeerConnections)
    }

    @Test
    fun `the active peer count scales with the connection budget`() {
        // A user who asks for 20 peers must not be handed 60; a user who asks for 500 must not be
        // allowed to overwhelm the CPU with 500 piece-assigning conversations.
        assertEquals(20, configured(peers = 20).maxConcurrentlyActivePeerConnectionsPerTorrent)
        assertEquals(60, configured(peers = 500).maxConcurrentlyActivePeerConnectionsPerTorrent)
        assertEquals(10, configured(peers = 1).maxConcurrentlyActivePeerConnectionsPerTorrent)
    }

    @Test
    fun `requests are pipelined with larger blocks`() {
        val config = configured(peers = 100)

        assertEquals(32 * 1024, config.transferBlockSize)
        assertEquals(128 * 1024, config.maxTransferBlockSize)
        assertTrue(config.maxOutstandingRequests >= 200, "block requests are not pipelined")
    }

    @Test
    fun `the disk queue is bounded so a slow drive throttles the peers`() {
        // The library default is Int.MAX_VALUE: on a disk slower than the network that means
        // unbounded buffering in memory rather than backpressure on the swarm.
        val config = configured(peers = 100)

        assertTrue(config.maxIOQueueSize in 1..512, "io queue is unbounded: ${config.maxIOQueueSize}")
    }

    @Test
    fun `piece verification is parallel but bounded`() {
        // Library default is a single hashing thread, which becomes the bottleneck on NVMe links.
        val threads = configured(peers = 100).numOfHashingThreads

        assertTrue(threads in 2..8, "$threads hashing threads")
    }

    @Test
    fun `slow peers are released without being banned for a minute`() {
        val config = configured(peers = 100)

        // A stalled assignment is reassigned quickly...
        assertTrue(config.maxPieceReceivingTime.toSeconds() in 30..120)
        // ...but the peer is kept, because in a small swarm it may be the only holder of what is left.
        assertTrue(config.timeoutedAssignmentPeerBanDuration.toSeconds() <= 30)
        // An interface change must not cost the swarm its known peers for half an hour.
        assertTrue(config.unreachablePeerBanDuration.toSeconds() <= 600)
    }

    @Test
    fun `the bind address is applied to the acceptor`() {
        val config = Config()
        applyPerformanceConfig(config, 50, InetAddress.getByName("10.0.0.5"))

        assertEquals("10.0.0.5", config.acceptorAddress?.hostAddress)
    }
}
