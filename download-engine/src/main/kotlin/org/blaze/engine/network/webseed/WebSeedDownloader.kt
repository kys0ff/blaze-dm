package org.blaze.engine.network.webseed

import bt.data.DataDescriptor
import bt.data.LocalBitfield
import bt.metainfo.Torrent
import bt.metainfo.TorrentId
import bt.net.buffer.BufferedData
import bt.net.buffer.DelegatingByteBufferView
import bt.runtime.BtRuntime
import bt.torrent.TorrentRegistry
import bt.torrent.data.BlockWrite
import bt.torrent.data.DataWorker
import bt.torrent.webseed.WebSeedFetcher
import bt.torrent.webseed.WebSeedPlanner
import bt.torrent.webseed.WebSeedSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives a GetRight-style web/HTTP seed (BEP-17 / BEP-19) alongside the swarm by feeding whole pieces
 * straight into `bt`'s [DataWorker], exactly as a peer would — so the library's normal verification,
 * bitfield accounting, progress and completion logic are unchanged and a web-seed byte is trusted the
 * same as a swarm byte.
 *
 * For every piece that is still missing (and not deselected) it maps the piece to file byte-ranges
 * with [WebSeedPlanner], fetches those ranges from a seed via the transport-agnostic
 * [WebSeedFetcher], concatenates them back into a piece, and writes it. A SHA-1 mismatch — surfaced
 * through the block's verification future — bans that seed URL for the rest of the session and the
 * piece is retried from the next seed, matching BEP-19's "discard the URL on a bad hash" rule.
 * Transport failures back off and retry without banning, so a momentary 500 or a connection drop does
 * not throw away an otherwise good mirror.
 *
 * Runs until the download's coroutine scope is cancelled (pause, completion, or the stall watchdog),
 * which is what bounds the retry loop against a permanently down seed.
 */
internal class WebSeedDownloader(
    private val runtime: BtRuntime,
    private val torrent: Torrent,
    private val fetcher: WebSeedFetcher,
    private val seedUrls: List<String>,
    private val logger: Logger,
) {

    private enum class Outcome { VERIFIED, ALREADY_DONE, RETRY }

    suspend fun run() {
        val seeds = seedUrls.map(String::trim).filter(String::isNotEmpty).distinct()
        if (seeds.isEmpty()) return

        val registry = runCatching { runtime.service(TorrentRegistry::class.java) }.getOrNull() ?: return
        val dataWorker = runCatching { runtime.service(DataWorker::class.java) }.getOrNull() ?: return
        val torrentId = torrent.torrentId
        val descriptor = awaitDataDescriptor(registry, torrentId) ?: return
        val bitfield: LocalBitfield = descriptor.bitfield

        val pieceCount = WebSeedPlanner.pieceCount(torrent)
        val banned = HashSet<String>()
        logger.info("Web-seed feed started for '{}' over {} seed(s)", torrent.name, seeds.size)

        for (piece in 0 until pieceCount) {
            var backoffMs = MIN_BACKOFF_MS
            while (true) {
                currentCoroutineContext().ensureActive()
                if (bitfield.isVerified(piece)) break
                if (bitfield.skippedBitmask.get(piece)) break
                val candidates = seeds.filterNot { banned.contains(it) }
                if (candidates.isEmpty()) {
                    logger.warn("Web seeds exhausted (all banned) before piece {}; leaving the rest to peers.", piece)
                    return
                }
                val bannedBefore = banned.size
                when (completePiece(dataWorker, torrentId, piece, candidates, banned)) {
                    Outcome.VERIFIED, Outcome.ALREADY_DONE -> break
                    Outcome.RETRY -> {
                        // Either every candidate hit a transport error, or one got banned this pass.
                        // A ban shrinks the candidate set, so retry immediately with what is left; a
                        // pure transport stall backs off before re-trying the same mirrors.
                        if (banned.size == bannedBefore) {
                            delay(backoffMs)
                            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        }
                    }
                }
            }
        }
        logger.info("Web-seed feed finished for '{}': all {} piece(s) satisfied or handed off.", torrent.name, pieceCount)
    }

    /**
     * Tries each candidate seed in turn to complete one piece. Returns [Outcome.VERIFIED] once the
     * piece hashes clean, [Outcome.ALREADY_DONE] if a peer beat us to it, and [Outcome.RETRY] when no
     * candidate could produce it this pass (transport failures, a queue-full rejection, or fresh bans).
     */
    private suspend fun completePiece(
        dataWorker: DataWorker,
        torrentId: TorrentId,
        piece: Int,
        candidates: List<String>,
        banned: MutableSet<String>,
    ): Outcome {
        val segments = WebSeedPlanner.planPiece(torrent, piece)
        if (segments.isEmpty()) return Outcome.ALREADY_DONE

        for (seed in candidates) {
            currentCoroutineContext().ensureActive()
            val bytes = fetchPiece(seed, segments) ?: continue  // transport error -> next mirror

            val buffer = BufferedData(DelegatingByteBufferView(ByteBuffer.wrap(bytes)))
            val write = runCatching { dataWorker.addBlock(torrentId, WebSeedPeer, piece, 0, buffer).awaitWrite() }
                .getOrElse {
                    // An unexpected executor failure is transient, not a bad hash - keep the seed.
                    logger.debug("Web-seed block write for piece {} failed: {}", piece, it.message)
                    return Outcome.RETRY
                }

            when {
                write.isRejected -> {
                    // A peer wrote the block first; the piece is (or is about to be) complete without us.
                    return Outcome.ALREADY_DONE
                }

                write.error.isPresent -> {
                    // Queue-full or write-time exception: nothing says the seed's bytes were wrong, so
                    // do not ban - just retry this piece (the caller backs off on an all-transport pass).
                    logger.debug("Web-seed write rejected for piece {}: {}", piece, write.error.get().message)
                    return Outcome.RETRY
                }

                else -> {
                    val verification = write.verificationFuture.orElse(null)
                        ?: return Outcome.RETRY  // write accepted but did not complete the chunk
                    if (verification.awaitVerified()) {
                        return Outcome.VERIFIED
                    }
                    // Bad hash from this mirror: ban it and try the next seed (BEP-19 discard rule).
                    logger.warn("Web seed {} produced a corrupt piece {}; banning it.", seed, piece)
                    banned += seed
                }
            }
        }
        return Outcome.RETRY
    }

    /**
     * Fetches every segment of a piece from [seed] and concatenates them in order. Any IOException -
     * including an under-length body, which [WebSeedFetcher] is contractually required to reject -
     * returns null so the caller tries a different mirror rather than writing bad bytes.
     */
    private suspend fun fetchPiece(seed: String, segments: List<WebSeedSegment>): ByteArray? {
        val total = segments.sumOf { it.length().toLong() }
        if (total > Int.MAX_VALUE) return null
        val piece = ByteArray(total.toInt())
        var pos = 0
        for (segment in segments) {
            val part = try {
                withContext(Dispatchers.IO) { fetcher.fetch(seed, segment) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.debug("Web seed {} failed to fetch {}: {}", seed, segment, e.message)
                return null
            }
            if (part.size != segment.length()) return null
            System.arraycopy(part, 0, piece, pos, part.size)
            pos += part.size
        }
        return piece
    }

    private suspend fun awaitDataDescriptor(registry: TorrentRegistry, torrentId: TorrentId): DataDescriptor? {
        val deadline = System.currentTimeMillis() + REGISTER_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val descriptor = runCatching { registry.getDescriptor(torrentId)?.orElse(null)?.dataDescriptor }
                .getOrNull()
            if (descriptor != null) return descriptor
            delay(REGISTER_POLL_MS)
        }
        logger.warn("Timed out waiting for the torrent data descriptor; skipping web seeds.")
        return null
    }

    private suspend fun CompletableFuture<BlockWrite>.awaitWrite(): BlockWrite = awaitValue()

    private suspend fun CompletableFuture<Boolean>.awaitVerified(): Boolean = awaitValue()

    private suspend fun <T> CompletableFuture<T>.awaitValue(): T = suspendCancellableCoroutine { cont ->
        whenComplete { value, error ->
            when (error) {
                null -> cont.resume(value)
                is CompletionException -> cont.resumeWithException(error.cause ?: error)
                else -> cont.resumeWithException(error)
            }
        }
        cont.invokeOnCancellation { cancel(true) }
    }

    private companion object {
        const val MIN_BACKOFF_MS = 100L
        const val MAX_BACKOFF_MS = 5_000L

        /** How long to wait for `bt` to create the data descriptor once the client has started. */
        const val REGISTER_WAIT_MS = 30_000L
        const val REGISTER_POLL_MS = 50L
    }
}
