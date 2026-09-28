package org.blaze.engine.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.network.TorrentNetworkClient
import org.blaze.engine.network.TorrentNetworkEvent
import org.blaze.engine.network.TorrentRuntimeProvider
import org.blaze.engine.portable.TorrentPortableFile
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression guard for "pause a torrent, resume it, and nothing ever starts until the app is
 * restarted". `bt` unregisters a torrent from the shared runtime's registry asynchronously, after
 * the processing chain finalizes; if the next session for the same torrent is allowed to begin
 * before that teardown lands, registration fails with
 * "Torrent already registered and data descriptor created" and the download stops within a second.
 * The client now waits the teardown out (non-cancellably) before its flow completes, which the
 * scheduler's pause-join respects - this test reproduces the exact pause-then-immediate-resume
 * sequence against a real shared runtime and asserts the second session survives.
 */
class TorrentPauseResumeTest {

    /** One provider for both sessions, exactly like the app's process-wide shared runtime. */
    private val provider = TorrentRuntimeProvider(idleGrace = 30.seconds)

    @Test
    fun `a session started right after a pause does not collide with the previous registration`(): Unit =
        runBlocking {
            val temp = Files.createTempDirectory("blaze-pause-resume")
            try {
                val torrent = writeTorrentFixture(temp)
                val destination = temp.resolve("data")
                val request = DownloadRequest.Torrent(
                    name = "pause-resume",
                    torrentSource = TorrentSource.File(torrent),
                    destination = destination,
                )
                val client = TorrentNetworkClient(runtimeProvider = provider)

                // ---- session 1: run until its metadata resolves, then pause exactly the way the
                // scheduler does: cancel the job and join it.
                val metadataSeen = CompletableDeferred<Unit>()
                val first = launch {
                    client.download(request).collect {
                        if (it is TorrentNetworkEvent.MetadataResolved) metadataSeen.complete(Unit)
                    }
                }
                metadataSeen.await()
                first.cancelAndJoin()

                assertTrue(
                    Files.isRegularFile(TorrentPortableFile.markerPath(destination)),
                    "a started torrent must leave its portable marker behind"
                )

                // ---- session 2: the user hits resume immediately, same torrent, same runtime.
                // Before the fix the collision surfaced within ~1s of start as an Error event.
                val events = mutableListOf<TorrentNetworkEvent>()
                val second = launch {
                    client.download(request).collect { events.add(it) }
                }
                delay(6_000) // generous multiple of the observed collision latency
                second.cancelAndJoin()

                val error = events.filterIsInstance<TorrentNetworkEvent.Error>().firstOrNull()
                assertNull(error, "resumed session collided with the paused one: ${error?.error}")
                assertTrue(
                    events.any { it is TorrentNetworkEvent.MetadataResolved },
                    "the resumed session must actually start; events were: $events",
                )
            } finally {
                provider.close()
                temp.toFile().deleteRecursively()
            }
        }

    // ---- minimal bencoder; the fixture is a real, parseable single-file torrent. ----

    private fun writeTorrentFixture(dir: Path): Path {
        val info = beDict(
            "length" to beInt(4L * 1024 * 1024),
            "name" to beStr("pause-resume.bin"),
            "piece length" to beInt(2L * 1024 * 1024),
            // Two 20-byte piece hashes, matching length / piece length exactly.
            "pieces" to beBytes(ByteArray(40)),
        )
        val file = dir.resolve("fixture.torrent")
        Files.write(
            file,
            beDict(
                "announce" to beStr("http://127.0.0.1:1/announce"), // dead by design; DHT/PEX carry discovery
                "info" to info,
                "created by" to beStr("blaze-test"),
            )
        )
        return file
    }

    private fun beStr(v: String): ByteArray = beBytes(v.toByteArray())

    private fun beBytes(v: ByteArray): ByteArray = "${v.size}:".toByteArray() + v

    private fun beInt(v: Long): ByteArray = "i${v}e".toByteArray()

    /** Bencode dictionary; keys are emitted in the byte order the format requires. */
    private fun beDict(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("d".toByteArray())
        entries.sortedBy { it.first }
            .forEach { (k, v) -> out.write(beStr(k)); out.write(v) }
        out.write("e".toByteArray())
        return out.toByteArray()
    }
}
