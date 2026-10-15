package org.blaze.engine.torrent

import bt.runtime.BtRuntime
import bt.runtime.Config
import bt.tracker.ITrackerService
import bt.tracker.http.HttpTrackerModule
import org.blaze.engine.network.UdpTrackerModule
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the peer-discovery capability that Phase 5 adds: `udp://` announce URLs become usable.
 *
 * bt ships the UDP tracker client inside `bt-core` (already a dependency) but, unlike the HTTP
 * tracker, never auto-wires it through the service loader - so a `udp://`-only torrent (the common
 * case for public swarms) used to resolve to no tracker at all and lean entirely on DHT/PEX.
 * [UdpTrackerModule] registers the factory; this asserts the runtime's tracker service now recognises
 * and builds a UDP tracker, that HTTP keeps working, and that unwired schemes (`wss://`) are still
 * honestly reported as unsupported. It builds a minimal runtime with only the tracker modules, so no
 * DHT bootstrap or socket activity happens - the numeric `udp://127.0.0.1` URL is parsed lazily and
 * never announced, which keeps the test fully offline.
 */
class UdpTrackerModuleTest {

    private val runtime = BtRuntime.builder(
        Config().apply {
            acceptorAddress = InetAddress.getLoopbackAddress()
            maxPeerConnections = 4
            maxPeerConnectionsPerTorrent = 4
        },
    )
        .module(HttpTrackerModule())
        .module(UdpTrackerModule())
        .disableAutomaticShutdown()
        .build()

    @AfterTest
    fun tearDown() {
        runCatching { runtime.shutdown() }
    }

    private fun trackerService(): ITrackerService = runtime.service(ITrackerService::class.java)

    @Test
    fun `a udp announce URL is now a supported tracker protocol`() {
        assertTrue(
            trackerService().isSupportedProtocol("udp://127.0.0.1:1337/announce"),
            "registering UdpTrackerModule must make the udp:// scheme resolvable",
        )
    }

    @Test
    fun `a udp announce URL resolves to a UDP tracker`() {
        // bt.tracker.udp.UdpTracker is package-private, so match on the resolved type name.
        assertEquals("UdpTracker", trackerService().getTracker("udp://127.0.0.1:1337/announce").javaClass.simpleName)
    }

    @Test
    fun `http trackers keep working alongside udp`() {
        assertTrue(trackerService().isSupportedProtocol("http://127.0.0.1:80/announce"))
        assertTrue(trackerService().isSupportedProtocol("https://127.0.0.1:443/announce"))
    }

    @Test
    fun `schemes we do not wire stay honestly unsupported`() {
        // WebTorrent's wss:// signalling is not provided by bt-core; the change must not pretend otherwise.
        assertFalse(trackerService().isSupportedProtocol("wss://127.0.0.1:1/announce"))
    }
}
