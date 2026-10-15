package org.blaze.engine.support

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A transparent loopback TCP relay with injectable one-way latency and an optional bandwidth cap, so
 * the swarm harness can measure torrent behavior against throttled and high-latency peers. It shovels
 * opaque bytes both directions, so it is entirely protocol-agnostic - the BitTorrent handshake, MSE
 * encryption and piece traffic all pass through untouched.
 *
 * TOR-5/PROMPT-2 8b.4: the fixed-vs-adaptive request-depth story only becomes visible when the link
 * actually has a bandwidth-delay product. The pre-existing harness connects the leecher straight to
 * the seeder (see [SwarmHarness]); a proxy in between lets a test point the leecher's static peer
 * source at [boundPort] while the seeder stays on its own port, then shape the in-between link.
 * This closes the half of TOR-6's reopen trigger ("injectable latency/throttle") that Phase 8a left
 * unmet.
 *
 * Latency is applied to every forwarded chunk in both directions, so an observed request/response
 * round trip is roughly `2 * oneWayDelayMillis`. [maxBytesPerSecond] paces only the amount forwarded
 * (a simple token bucket); 0 disables shaping.
 */
class LatencyProxy(
    upstreamPort: Int,
    private val oneWayDelayMillis: Long = 0L,
    private val maxBytesPerSecond: Long = 0L,
    upstreamHost: String = "127.0.0.1",
) : AutoCloseable {

    private val running = AtomicBoolean(true)
    private val server = ServerSocket().apply { bind(InetSocketAddress(upstreamHost, 0)) }

    /** The ephemeral loopback port the leecher should connect to; the proxy forwards to [upstreamPort]. */
    val boundPort: Int = server.localPort

    private val acceptThread = thread(isDaemon = true, name = "latency-proxy-accept-$boundPort") {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            thread(isDaemon = true, name = "latency-proxy-relay-$boundPort") { relay(client, upstreamHost, upstreamPort) }
        }
    }

    private fun relay(client: Socket, upstreamHost: String, upstreamPort: Int) {
        val upstream = try {
            Socket().apply { connect(InetSocketAddress(upstreamHost, upstreamPort), 5_000) }
        } catch (e: IOException) {
            runCatching { client.close() }
            return
        }
        // The two directions must both end before the sockets are released; either pump closing the
        // pair unblocks the other's blocking read.
        thread(isDaemon = true, name = "latency-proxy-down") { pump(upstream, client) }
        thread(isDaemon = true, name = "latency-proxy-up") { pump(client, upstream) }
    }

    private fun pump(from: Socket, to: Socket) {
        val buffer = ByteArray(64 * 1024)
        val bucket = if (maxBytesPerSecond > 0) TokenBucket(maxBytesPerSecond) else null
        try {
            from.getInputStream().use { input ->
                to.getOutputStream().use { output ->
                    while (running.get()) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bucket?.acquire(read)
                        if (oneWayDelayMillis > 0) Thread.sleep(oneWayDelayMillis)
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                }
            }
        } catch (e: IOException) {
            // One side tore down; the sibling pump will observe EOF/reset and unwind too.
        } finally {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        runCatching { acceptThread.interrupt() }
    }

    /** Minimal continuous token bucket used to pace bytes/second through the proxy. */
    private class TokenBucket(private val bytesPerSecond: Long) {
        private var tokens = bytesPerSecond.toDouble()
        private var lastRefillNanos = System.nanoTime()

        fun acquire(bytes: Int) {
            var need = bytes.toDouble()
            while (need > 0) {
                val now = System.nanoTime()
                tokens += (now - lastRefillNanos) / 1_000_000_000.0 * bytesPerSecond
                lastRefillNanos = now
                if (tokens > bytesPerSecond) tokens = bytesPerSecond.toDouble()
                if (tokens >= 1.0) {
                    val take = minOf(need, tokens)
                    tokens -= take
                    need -= take
                } else {
                    Thread.sleep(1)
                }
            }
        }
    }
}
