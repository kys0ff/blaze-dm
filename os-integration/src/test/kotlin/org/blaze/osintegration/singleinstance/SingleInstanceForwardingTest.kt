package org.blaze.osintegration.singleinstance

import kotlinx.coroutines.flow.first
import org.blaze.download.api.ServicePaths
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.osintegration.ExternalOpenRequest
import org.blaze.osintegration.PathProbe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end single-instance behaviour on the real platform transport (an AF_UNIX socket in a temp
 * data directory, with the loopback-TCP fallback behind it where unix sockets are unavailable).
 *
 * Everything here is real I/O, so the tests wait on *conditions with deadlines* instead of virtual
 * time: that is precisely the startup/warm-launch race users hit from a browser. The "second
 * launcher" role runs as a genuine forked JVM (see SecondLauncherProcess.kt) because POSIX record
 * locks - the foundation of [InstanceLock] - are per-process and a same-JVM simulation would
 * trivially pass for the wrong reason.
 */
class SingleInstanceForwardingTest {

    private val hex40 = "0123456789abcdef0123456789abcdef01234567"

    private fun tempStorageDir(): Path = Files.createTempDirectory("blaze-instance-test")

    /** A torrent file that genuinely exists, so the owner-side re-validation probe accepts the forward. */
    private fun realTorrent(dir: Path, name: String = "movie.torrent"): Path =
        dir.resolve(name).also { if (!Files.exists(it)) Files.writeString(it, "d8:announce0:e") }

    private fun awaitUntil(deadlineMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("condition not met within ${deadlineMs}ms")
    }

    /** Runs the second-launcher role in its own JVM, exactly as a browser activation would. */
    private fun runSecondLauncherProcess(storageDir: Path, vararg rawArgs: String): Pair<Int, String> {
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val process = ProcessBuilder(
            java.toString(),
            "-cp", System.getProperty("java.class.path"),
            "org.blaze.osintegration.singleinstance.SecondLauncherProcessKt",
            storageDir.toString(),
            *rawArgs,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = if (process.waitFor(60, TimeUnit.SECONDS)) process.exitValue() else {
            process.destroy()
            -99
        }
        return exit to output
    }

    // ── role decision: owner vs forwarding launcher ────────────────────────────────────────────

    @Test
    fun `the first process owns the directory and its argv reaches the inbox`() {
        val dir = tempStorageDir()
        val inbox = ExternalOpenInbox()
        val torrent = ExternalOpenRequest.TorrentFile(realTorrent(dir))

        val outcome = SingleInstanceCoordinator.start(dir, inbox, listOf(torrent))

        assertTrue(outcome is SingleInstanceOutcome.Owner, "first launcher must own the instance")
        // Owner path submits this process's own argv - the cold-start request is never dropped.
        assertEquals(listOf(torrent), inbox.pending.value)
        outcome.coordinator.close()
    }

    @Test
    fun `a second launcher process forwards to the running instance and exits`() {
        val dir = tempStorageDir()
        val inboxA = ExternalOpenInbox()
        val owner = SingleInstanceCoordinator.start(dir, inboxA, emptyList())
        assertTrue(owner is SingleInstanceOutcome.Owner)

        // The spec's warm-start scenario, with a real second OS process:
        // instance A running, B launched with a magnet + torrent, B forwards, B exits, A processes.
        val magnetRaw = "magnet:?xt=urn:btih:$hex40&dn=Second%20Window"
        val torrent = realTorrent(dir, "warm.torrent")
        val (exit, out) = runSecondLauncherProcess(dir, magnetRaw, torrent.toString())

        assertEquals(0, exit, "the forwarding launcher must exit cleanly; out=$out")
        assertTrue("OUTCOME=Forwarded" in out, "the warm launcher must forward, not start; out=$out")
        awaitUntil { inboxA.pending.value.containsAll(listOf(ExternalOpenRequest.MagnetUri(magnetRaw), ExternalOpenRequest.TorrentFile(torrent))) }
        (owner as SingleInstanceOutcome.Owner).coordinator.close()
    }

    @Test
    fun `releasing ownership lets the next process become the owner`() {
        val dir = tempStorageDir()
        val first = SingleInstanceCoordinator.start(dir, ExternalOpenInbox(), emptyList())
        assertTrue(first is SingleInstanceOutcome.Owner)
        first.coordinator.close()

        // Kernel locks die with the holder; a fresh process must be able to own the directory again.
        val (exit, out) = runSecondLauncherProcess(dir)
        assertEquals(0, exit, "out=$out")
        assertTrue("OUTCOME=Owner" in out, "stale ownership must not lock the next launch out; out=$out")
    }

    @Test
    fun `a lock held without a listener degrades to Unreachable instead of dropping requests`() {
        val dir = tempStorageDir()
        val holder = InstanceLock(ServicePaths.instanceLockFileFor(dir))
        assertTrue(holder.acquire())

        val (exit, out) = runSecondLauncherProcess(dir, realTorrent(dir).toString())
        assertEquals(3, exit, "no owner to forward to -> degrade, never exit; out=$out")
        assertTrue("OUTCOME=Unreachable" in out, "out=$out")
        // The degraded instance still carries its request locally: nothing was dropped.
        assertTrue("KEPT=1" in out, "the degraded launcher must keep the request; out=$out")
        holder.close()
    }

    // ── authentication and framing of the channel ─────────────────────────────────────────────

    @Test
    fun `frames without the shared token or with junk content are refused`() {
        val dir = tempStorageDir()
        val inbox = ExternalOpenInbox()
        val owner = SingleInstanceCoordinator.start(dir, inbox, emptyList())
        assertTrue(owner is SingleInstanceOutcome.Owner)

        val magnet = ExternalOpenRequest.MagnetUri("magnet:?xt=urn:btih:$hex40")
        val socketFile = ServicePaths.instanceSocketFileFor(dir)
        val tcpFile = ServicePaths.instanceTcpFileFor(dir)

        // Right transport, wrong (or missing) secret: filesystem reachability is not authorization.
        assertFalse(InstanceClient.send(socketFile, tcpFile, InstanceProtocol.encode("forged-token", listOf(magnet))))
        assertFalse(InstanceClient.send(socketFile, tcpFile, InstanceProtocol.encode("", listOf(magnet))))
        // Structured junk that never even decodes.
        assertFalse(InstanceClient.send(socketFile, tcpFile, "not-json\nat-all"))
        assertEquals(emptyList(), inbox.pending.value, "a refused frame must leave the queue untouched")

        // And the genuine frame still works over the same channel.
        assertTrue(InstanceClient.send(socketFile, tcpFile, InstanceProtocol.encode(
            InstanceTokenStore(ServicePaths.instanceTokenFileFor(dir)).readPublished()!!, listOf(magnet),
        )))
        awaitUntil { inbox.pending.value == listOf(magnet) }
        (owner as SingleInstanceOutcome.Owner).coordinator.close()
    }

    @Test
    fun `an oversized frame is cut off before it can balloon memory`() {
        val input = ByteArrayInputStream(ByteArray(InstanceProtocol.MAX_FRAME_BYTES.toInt() + 1))
        assertNull(InstanceProtocol.readLineCapped(input), "a flood over the cap must be rejected, not buffered")
    }

    @Test
    fun `a half-closing peer never strands the accept loop`() {
        val dir = tempStorageDir()
        val inbox = ExternalOpenInbox()
        val owner = SingleInstanceCoordinator.start(dir, inbox, emptyList())
        assertTrue(owner is SingleInstanceOutcome.Owner)

        // Connect and hang up without sending a frame: the server must survive to serve the next one.
        runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use {
                it.connect(UnixDomainSocketAddress.of(ServicePaths.instanceSocketFileFor(dir)))
            }
        } // AF_UNIX unavailable -> the fallback listener behaves the same for abrupt closes.

        val magnet = ExternalOpenRequest.MagnetUri("magnet:?xt=urn:btih:$hex40&dn=After%20Rude%20Client")
        val token = InstanceTokenStore(ServicePaths.instanceTokenFileFor(dir)).readPublished()!!
        assertTrue(InstanceClient.send(
            ServicePaths.instanceSocketFileFor(dir), ServicePaths.instanceTcpFileFor(dir),
            InstanceProtocol.encode(token, listOf(magnet)),
        ))
        awaitUntil { inbox.pending.value == listOf(magnet) }
        (owner as SingleInstanceOutcome.Owner).coordinator.close()
    }

    // ── wire format fidelity for hostile/exotic content ───────────────────────────────────────

    @Test
    fun `unicode paths spaces and verbatim magnets survive the wire and re-validation`() {
        val parser = ExternalOpenParser { PathProbe.REGULAR_FILE } // stubbed probe: pure wire test
        val requests = listOf(
            ExternalOpenRequest.TorrentFile(Paths.get("/home/user/Μovіes/видео — big file (2).torrent")),
            ExternalOpenRequest.MagnetUri(
                "magnet:?xt=urn:btih:$hex40&dn=Скачать%20%E2%98%95&tr=udp%3A%2F%2Ftracker%3A6969&key=a%26b"
            ),
        )

        val token = "test-token"
        val out = ByteArrayOutputStream()
        InstanceProtocol.writeFrame(out, InstanceProtocol.encode(token, requests))
        val line = InstanceProtocol.readLineCapped(ByteArrayInputStream(out.toByteArray()))!!

        val frame = InstanceProtocol.decode(line)!!
        assertEquals(token, frame.token)
        assertEquals(requests, InstanceProtocol.toRequests(frame, parser), "full round-trip fidelity")
    }

    @Test
    fun `malicious forwarded payloads are dropped by re-validation, unknown kinds refused`() {
        val parser = ExternalOpenParser { PathProbe.MISSING } // nothing exists on this "machine"
        val hostile = InstanceProtocol.OpenFrame("t", listOf(
            InstanceProtocol.WireOpen("torrent", "rm -rf /"),           // not a torrent at all
            InstanceProtocol.WireOpen("future-kind", "anything"),        // a kind this build cannot know
        ))
        assertNull(InstanceProtocol.toRequests(hostile, parser), "a partially corrupt frame is refused wholesale")

        // Positive control: the machinery works - a genuine frame of an existing file passes through.
        val happyParser = ExternalOpenParser { PathProbe.REGULAR_FILE }
        val happy = ExternalOpenRequest.MagnetUri("magnet:?xt=urn:btih:$hex40")
        val happyFrame = InstanceProtocol.decode(InstanceProtocol.encode("t", listOf(happy)))!!
        assertEquals(listOf(happy), InstanceProtocol.toRequests(happyFrame, happyParser))
    }

    @Test
    fun `malformed utf-8 bytes on the wire reject the frame instead of decoding to garbage`() {
        // 0xC3 0x28 is not valid UTF-8: strict decoding must reject, never produce replacement noise.
        val broken = byteArrayOf('a'.code.toByte(), 0xC3.toByte(), 0x28.toByte(), '\n'.code.toByte())
        assertNull(InstanceProtocol.readLineCapped(ByteArrayInputStream(broken)))
        assertEquals("valid", InstanceProtocol.readLineCapped(ByteArrayInputStream("valid\n".toByteArray())))
    }
}
