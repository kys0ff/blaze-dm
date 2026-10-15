package org.blaze.engine.network

import bt.Bt
import bt.bencoding.model.BEObject
import bt.bencoding.serializers.BEEncoder
import bt.bencoding.serializers.BEParser
import bt.bencoding.types.BEList
import bt.bencoding.types.BEMap
import bt.bencoding.types.BEString
import bt.event.EventSink
import bt.metainfo.MetadataConstants
import bt.metainfo.Torrent
import bt.metainfo.TorrentId
import bt.runtime.BtClient
import bt.runtime.BtRuntime
import bt.torrent.TorrentRegistry
import bt.torrent.fileselector.FilePriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadFileMetadata
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.portable.PortableManifest
import org.blaze.engine.portable.PortableTorrentFile
import org.blaze.engine.portable.TorrentPortableFile
import org.blaze.engine.storage.TorrentContentStorage
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * @param peerDiscoveryTimeout how long to wait for the first peer once metadata is known.
 * @param stallTimeout how long to tolerate zero downloaded bytes after peers have been seen.
 * @param metadataTimeout how long to wait for magnet metadata (BEP 9) before giving up.
 * @param maxPeerConnections upper bound on simultaneous peer connections (from settings).
 * @param enableSeeding keep uploading after all pieces are downloaded, governed by the seed limits below.
 * @param seedTimeLimitMinutes how long to keep seeding once the download completes; ignored when [unlimitedSeeding].
 * @param unlimitedSeeding keep seeding until the download is stopped manually (the time limit no longer applies).
 * @param seedRatioLimit stop seeding once uploaded/downloaded reaches this share ratio; 0 disables the ratio cap.
 */
class TorrentNetworkClient(
    private val peerDiscoveryTimeout: Duration = 120.seconds,
    private val stallTimeout: Duration = 5.minutes,
    private val metadataTimeout: Duration = 3.minutes,
    private val maxPeerConnections: Int = 200,
    private val enableSeeding: Boolean = false,
    private val seedTimeLimitMinutes: Int = 30,
    private val unlimitedSeeding: Boolean = false,
    private val seedRatioLimit: Double = 0.0,
    private val runtimeProvider: TorrentRuntimeProvider = TorrentRuntimeProvider.GLOBAL
) {
    private val logger = LoggerFactory.getLogger(TorrentNetworkClient::class.java)

    private enum class StopReason { DOWNLOADED, SEED_COMPLETE, NO_PEERS, STALLED, METADATA_TIMEOUT }

    fun download(request: DownloadRequest.Torrent): Flow<TorrentNetworkEvent> = channelFlow {
        logger.info(
            "Starting torrent download. Source: {}, Destination: {}",
            request.torrentSource,
            request.destination
        )
        try {
            val source = request.torrentSource

            // Validate cheaply *before* spinning up DHT / sockets.
            if (source is TorrentSource.File) {
                if (!Files.isRegularFile(source.path)) {
                    logger.error("Torrent file not found: {}", source.path.toAbsolutePath())
                    send(TorrentNetworkEvent.Error(DownloadError.InvalidTorrent))
                    return@channelFlow
                }
                if (!isValidTorrentFile(source.path)) {
                    logger.error("Not a valid .torrent file: {}", source.path.toAbsolutePath())
                    send(TorrentNetworkEvent.Error(DownloadError.InvalidTorrent))
                    return@channelFlow
                }
            }
            // A remote metainfo URL is parsed up front too, so a malformed one fails with
            // InvalidTorrent instead of surfacing as an opaque network error later.
            val torrentUrl = if (source is TorrentSource.Url) {
                val parsed = runCatching { java.net.URI(source.url.trim()).toURL() }
                    .getOrNull()
                if (parsed == null || parsed.protocol !in setOf("http", "https")) {
                    logger.error("Invalid torrent metainfo URL: {}", source.url)
                    send(TorrentNetworkEvent.Error(DownloadError.InvalidTorrent))
                    return@channelFlow
                }
                parsed
            } else {
                null
            }

            val destination = request.destination.toAbsolutePath().normalize()
            Files.createDirectories(destination)
            // Blaze's storage, not bt's: `FileSystemStorage` would root a multi-file torrent at
            // `<destination>/<name from the metainfo>` and give the download a second folder level
            // inside the one the service already made for it.
            val storage = TorrentContentStorage(destination)

            // Shared with every other torrent in the app: one DHT node, one acceptor, and no
            // re-bootstrap when a download is retried or started right after another finished.
            val runtime = runtimeProvider.acquire(torrentRuntimeFingerprint(maxPeerConnections)) { config ->
                applyPerformanceConfig(config, maxPeerConnections, resolveBindAddress())
            }
            try {
                // URL sources wait on bt's metainfo fetch the same way magnets wait on BEP 9.
                val monitor = Monitor(metadataPending = source is TorrentSource.Magnet || source is TorrentSource.Url)
                val totalSize = AtomicLong(-1L)
                val stopReason = AtomicReference<StopReason?>(null)
                val torrentRef = AtomicReference<Torrent?>(null)
                // Portable-marker bookkeeping: the metainfo arrives once with the metadata, the
                // completed-piece hint is refreshed as pieces verify, and every write bumps the
                // generation so a torn overwrite can never masquerade as the latest state.
                val portableBytes = AtomicReference<ByteArray?>(null)
                val markerGeneration = AtomicInteger(1)
                val lastHintedPieces = AtomicInteger(0)
                val lastObservedPieces = AtomicInteger(0)

                val clientBuilder = Bt.client(runtime)
                    .storage(storage)
                    .afterTorrentFetched { torrent ->
                        torrentRef.set(torrent)
                        logger.info(
                            "Torrent metadata resolved: name='{}', size={} bytes",
                            torrent.name,
                            torrent.size
                        )
                        totalSize.set(torrent.size)
                        monitor.onMetadataResolved(torrent.size)
                        logTrackers(torrent)

                        val files = torrent.files.map { file ->
                            DownloadFileMetadata(
                                path = file.pathElements.joinToString(File.separator),
                                size = file.size
                            )
                        }

                        val metadata = torrent.source.metadata
                        val bytes = if (metadata.isPresent) {
                            metadata.get()
                        } else {
                            try {
                                reconstructTorrentFile(torrent)
                            } catch (e: Exception) {
                                logger.error("Failed to reconstruct torrent file from metadata", e)
                                null
                            }
                        }
                        portableBytes.set(bytes)
                        trySend(
                            TorrentNetworkEvent.MetadataResolved(
                                torrent.name,
                                torrent.size,
                                bytes,
                                files
                            )
                        )

                        // Make this download portable: drop a self-contained metainfo marker at the
                        // torrent's destination root so the folder can be moved to another machine
                        // and resumed without the original `.torrent`/magnet. It is advisory-free —
                        // `bt` re-verifies every piece against the embedded hashes on resume.
                        if (bytes != null) {
                            runCatching {
                                TorrentPortableFile.write(
                                    TorrentPortableFile.markerPath(destination),
                                    torrentManifest(torrent, bytes, request.fileIndices),
                                    generation = 1
                                )
                            }.onFailure { logger.warn("Could not write the portable torrent marker: {}", it.message) }
                        }
                    }

                if (request.fileIndices != null) {
                    val selectedSet = request.fileIndices.toSet()
                    val counter = AtomicInteger(0)
                    clientBuilder.fileSelector { file ->
                        val torrent = torrentRef.get()
                        val index = if (torrent != null) {
                            torrent.files.indexOf(file)
                        } else {
                            counter.getAndIncrement()
                        }
                        if (index == -1 || selectedSet.contains(index)) {
                            FilePriority.NORMAL_PRIORITY
                        } else {
                            FilePriority.SKIP
                        }
                    }
                }

                when (source) {
                    is TorrentSource.File -> clientBuilder.torrent(source.path.toUri().toURL())
                    is TorrentSource.Magnet -> {
                        logger.info("Using magnet link source: {}", source.uri)
                        clientBuilder.magnet(source.uri)
                    }
                    is TorrentSource.Url -> {
                        logger.info("Using remote torrent metainfo: {}", source.url)
                        clientBuilder.torrent(torrentUrl)
                    }
                }

                val btClient = clientBuilder.build()

                fun requestStop(reason: StopReason) {
                    if (stopReason.compareAndSet(null, reason)) btClient.stop()
                }

                // Keep the portable marker's progress hint current so an imported folder previews
                // with a truthful "bytes available" on another machine; the metainfo itself never
                // changes. Writes are monotonic in pieces and rate-limited by the tick cadence.
                fun refreshPortableMarker(piecesComplete: Int) {
                    val torrent = torrentRef.get() ?: return
                    val bytes = portableBytes.get() ?: return
                    if (piecesComplete <= 0 || piecesComplete <= lastHintedPieces.get()) return
                    runCatching {
                        TorrentPortableFile.write(
                            TorrentPortableFile.markerPath(destination),
                            torrentManifest(torrent, bytes, request.fileIndices, piecesComplete),
                            generation = markerGeneration.incrementAndGet().toLong()
                        )
                        lastHintedPieces.set(piecesComplete)
                    }.onFailure { logger.warn("Could not refresh the portable torrent marker: {}", it.message) }
                }

                val downloadMeter = RateMeter()
                val uploadMeter = RateMeter()
                val ticks = AtomicInteger(0)

                val future: CompletableFuture<*> = btClient.startAsync({ state ->
                    val peers = state.connectedPeers.size
                    monitor.onProgress(state.downloaded, state.uploaded, peers)

                    val downloadSpeed = downloadMeter.update(state.downloaded)
                    val uploadSpeed = uploadMeter.update(state.uploaded)

                    // Progress is high-frequency and lossy by nature; metadata/terminal events are not (see buffer below).
                    trySend(
                        TorrentNetworkEvent.Progress(
                            downloadedBytes = state.downloaded,
                            totalBytes = totalSize.get(),
                            downloadSpeed = downloadSpeed,
                            uploadSpeed = uploadSpeed,
                            peers = peers,
                            piecesComplete = state.piecesComplete,
                            piecesTotal = state.piecesTotal,
                            piecesRemaining = state.piecesRemaining
                        )
                    )

                    if (ticks.incrementAndGet() % 10 == 0) {
                        logger.debug(
                            "Torrent status: peers={}, down={} B/s, pieces={}/{}",
                            peers, downloadSpeed, state.piecesComplete, state.piecesTotal
                        )
                    }

                    lastObservedPieces.set(state.piecesComplete)
                    if (state.piecesComplete > 0 && ticks.get() % MARKER_REFRESH_TICKS == 0) {
                        refreshPortableMarker(state.piecesComplete)
                    }

                    if (state.piecesTotal > 0 && state.piecesRemaining == 0) {
                        // First tick where every piece is local. Without seeding we stop right away
                        // (previous behavior); with seeding we keep uploading and let the watchdog
                        // stop us once a configured seed limit (time or ratio) is reached - or never,
                        // when seeding is unlimited with no ratio cap.
                        if (!monitor.piecesDone) {
                            monitor.piecesDone = true
                            if (enableSeeding) {
                                if (!unlimitedSeeding && seedTimeLimitMinutes > 0) {
                                    monitor.seedDeadlineMs = nowMs() + seedTimeLimitMinutes * 60_000L
                                }
                                logger.info(seedingStartedMessage())
                            } else {
                                requestStop(StopReason.DOWNLOADED)
                            }
                        }
                    }
                }, 1000)

                val watchdog = launch {
                    while (isActive) {
                        delay(2_000.milliseconds)
                        val (reason, limit) = monitor.evaluate() ?: continue
                        logger.warn("Watchdog triggered stop. Reason: {}, Limit: {}", reason, limit)
                        requestStop(reason)
                        break
                    }
                }

                try {
                    future.awaitCompletion()

                    // bt may finish the future on its own (e.g. between two ticks), so fall back to observed state.
                    val reason =
                        stopReason.get() ?: if (monitor.piecesDone) StopReason.DOWNLOADED else null
                    when (reason) {
                        StopReason.DOWNLOADED, StopReason.SEED_COMPLETE -> {
                            logger.info("Torrent download completed successfully.")
                            // Strip the portable marker so the finished folder is a plain, ordinary
                            // copy of the torrent's files with nothing engine-specific left behind.
                            TorrentPortableFile.delete(TorrentPortableFile.markerPath(destination))
                            send(TorrentNetworkEvent.Completed)
                        }

                        StopReason.NO_PEERS -> {
                            logger.warn("Torrent download stopped: no peers found within timeout.")
                            send(TorrentNetworkEvent.Error(DownloadError.NetworkFailure("No peers found")))
                        }

                        StopReason.METADATA_TIMEOUT -> {
                            logger.warn("Torrent download stopped: metadata not received within timeout.")
                            send(TorrentNetworkEvent.Error(DownloadError.NetworkFailure("Timed out fetching torrent metadata")))
                        }

                        StopReason.STALLED -> {
                            logger.warn("Torrent download stopped: stalled during download.")
                            send(TorrentNetworkEvent.Error(DownloadError.NetworkFailure("Download stalled")))
                        }

                        null -> {
                            // Previously this emitted nothing, leaving the UI stuck in "downloading".
                            logger.warn("Torrent client stopped without a reason and without completing.")
                            send(TorrentNetworkEvent.Error(DownloadError.NetworkFailure("Download stopped unexpectedly")))
                        }
                    }
                } catch (e: CancellationException) {
                    logger.info("Torrent download cancelled.")
                    throw e
                } finally {
                    watchdog.cancel()
                    // On cancellation the future's own `whenComplete -> stop()` inside bt has
                    // already run, so this is typically a no-op (DefaultClient.stop() ignores a
                    // start it has already consumed); it stays for the paths that end without
                    // the future completing.
                    runCatching { if (btClient.isStarted) btClient.stop() }
                    // `bt` only forgets a registered torrent when its processing chain finalizes,
                    // which happens asynchronously *after* stop() - and a chain still parked waiting
                    // for a first peer never finalizes at all until the runtime shuts down. Until
                    // the teardown lands, the next session for the same torrent - a resume right
                    // after a pause, an automatic retry, or the add-download metadata probe giving
                    // way to the real transfer - dies with "Torrent already registered and data
                    // descriptor created". The scheduler joins this job before it will start the
                    // next one, so waiting the teardown out here (non-cancellably: when pausing,
                    // the job is already canceled) turns pause -> resume from a race into a
                    // deterministic sequence.
                    withContext(NonCancellable + Dispatchers.IO) {
                        refreshPortableMarker(lastObservedPieces.get())
                        // awaitTorrentSettled warns on failure itself; nothing actionable here.
                        awaitTorrentSettled(runtime, btClient, torrentRef.get())
                    }
                }
            } finally {
                runtimeProvider.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Torrent download failed due to an exception", e)
            send(
                TorrentNetworkEvent.Error(
                    DownloadError.NetworkFailure(
                        e.message ?: "Torrent error"
                    )
                )
            )
        }
    }
        // UNLIMITED so trySend() from bt's threads can never drop MetadataResolved / Completed / Error.
        // Cost is negligible: Progress arrives ~1/s.
        .buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.IO)

    /** Tracks phase + liveness for the watchdog. All timestamps are monotonic. */
    private class Monitor(@Volatile var metadataPending: Boolean) {
        @Volatile
        var piecesDone: Boolean = false

        @Volatile
        var sawPeer: Boolean = false

        @Volatile
        var phaseStartMs: Long = nowMs()

        @Volatile
        var lastDataMs: Long = nowMs()

        /** Monotonic-ms deadline for the seeding phase; 0 means no time-based seed deadline. */
        @Volatile
        var seedDeadlineMs: Long = 0L

        /** Total bytes uploaded so far; used to enforce the share-ratio seed cap. */
        @Volatile
        var uploadedBytes: Long = 0L

        /** Full torrent size in bytes; the denominator of the share ratio. */
        @Volatile
        var totalSizeBytes: Long = 0L

        @Volatile
        private var lastDownloaded: Long = 0L

        fun onMetadataResolved(totalSize: Long) {
            metadataPending = false
            totalSizeBytes = totalSize
            phaseStartMs =
                nowMs() // peer-discovery clock starts once we actually know what to download
        }

        fun onProgress(downloaded: Long, uploaded: Long, peers: Int) {
            uploadedBytes = uploaded
            if (peers > 0 && !sawPeer) {
                sawPeer = true
                lastDataMs = nowMs() // stall clock starts at first peer
            }
            // Only real data counts as progress. Idle-but-connected (choked) peers must not
            // keep resetting the stall timer like they did before.
            if (downloaded > lastDownloaded) {
                lastDownloaded = downloaded
                lastDataMs = nowMs()
            }
        }
    }

    private fun Monitor.evaluate(): Pair<StopReason, Duration>? {
        val now = nowMs()
        return when {
            // The download already finished, so the stall / no-peer timers no longer apply.
            // Seeding ends only on a configured limit; with no limit armed we keep going.
            piecesDone -> seedingStop(now)

            metadataPending ->
                (StopReason.METADATA_TIMEOUT to metadataTimeout).takeIf { now - phaseStartMs > metadataTimeout.inWholeMilliseconds }

            !sawPeer ->
                (StopReason.NO_PEERS to peerDiscoveryTimeout).takeIf { now - phaseStartMs > peerDiscoveryTimeout.inWholeMilliseconds }

            else ->
                (StopReason.STALLED to stallTimeout).takeIf { now - lastDataMs > stallTimeout.inWholeMilliseconds }
        }
    }

    /**
     * Decides whether the seeding phase should stop, checked on every watchdog tick once every
     * piece is local. A time deadline (unless [unlimitedSeeding]) and a share-ratio cap (when > 0)
     * are independent; whichever is reached first wins. Returns null to keep seeding.
     */
    private fun Monitor.seedingStop(now: Long): Pair<StopReason, Duration>? {
        val timeUp = seedDeadlineMs in 1..<now
        if (timeUp) return StopReason.SEED_COMPLETE to seedTimeLimitMinutes.minutes

        val ratioUp = seedRatioLimit > 0.0 && totalSizeBytes > 0 &&
            uploadedBytes.toDouble() >= seedRatioLimit * totalSizeBytes
        if (ratioUp) return StopReason.SEED_COMPLETE to Duration.INFINITE

        return null
    }

    /** Human-readable description of the seeding policy armed for this transfer. */
    private fun seedingStartedMessage(): String = buildString {
        append("Download complete; seeding")
        val until = mutableListOf<String>()
        if (unlimitedSeeding) {
            until += "until stopped manually"
        } else if (seedTimeLimitMinutes > 0) {
            until += "for the next $seedTimeLimitMinutes minute(s)"
        }
        if (seedRatioLimit > 0.0) {
            until += "or until share ratio $seedRatioLimit is reached"
        }
        if (until.isEmpty()) {
            until += "indefinitely (no seed limits set)"
        }
        append(" ").append(until.joinToString(" and ")).append(".")
    }

    /** Exponentially smoothed bytes/second so the UI doesn't jitter. */
    private class RateMeter(private val alpha: Double = 0.3) {
        private var initialized = false
        private var lastBytes = 0L
        private var lastNanos = 0L
        private var smoothed = 0.0

        @Synchronized
        fun update(totalBytes: Long): Long {
            val now = System.nanoTime()
            if (!initialized) {
                initialized = true
                lastBytes = totalBytes
                lastNanos = now
                return 0L
            }
            val seconds = (now - lastNanos) / 1_000_000_000.0
            if (seconds <= 0.0) return smoothed.toLong()
            val instant = ((totalBytes - lastBytes) / seconds).coerceAtLeast(0.0)
            lastBytes = totalBytes
            lastNanos = now
            smoothed = if (smoothed == 0.0) instant else alpha * instant + (1 - alpha) * smoothed
            return smoothed.toLong()
        }
    }

    private suspend fun CompletableFuture<*>.awaitCompletion() {
        if (isDone) {
            try {
                join()
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }
            return
        }
        return suspendCancellableCoroutine { cont ->
            whenComplete { _, throwable ->
                when (throwable) {
                    null -> cont.resume(Unit)
                    is CompletionException -> cont.resumeWithException(throwable.cause ?: throwable)
                    else -> cont.resumeWithException(throwable)
                }
            }
            cont.invokeOnCancellation { cancel(true) }
        }
    }

    private fun isValidTorrentFile(path: Path): Boolean = runCatching {
        BEParser(Files.readAllBytes(path)).readMap().value.containsKey(MetadataConstants.INFOMAP_KEY)
    }.getOrDefault(false)

    /**
     * Logs which announce URLs in the torrent are usable by the wired tracker clients. HTTP(S) comes
     * from bt's tracker module and `udp://` from the UDP tracker factory we register on the runtime
     * (bt ships the client in bt-core but never auto-wires it). Anything else - e.g. `wss://` - is
     * unusable here, so the swarm would then depend entirely on DHT/PEX; logging it makes "0 peers"
     * much easier to diagnose.
     */
    private fun logTrackers(torrent: Torrent) {
        val urls = buildList {
            torrent.announceKey.ifPresent { key ->
                if (key.isMultiKey) key.trackerUrls.forEach { addAll(it) } else add(key.trackerUrl)
            }
        }
        val usable = urls.filter {
            it.startsWith("http://") || it.startsWith("https://") || it.startsWith("udp://")
        }
        logger.debug("Trackers: {} total, {} usable (HTTP/HTTPS/UDP): {}", urls.size, usable.size, urls)
        if (usable.isEmpty()) {
            logger.warn("No usable HTTP(S)/UDP tracker in torrent; peers can only come from DHT / peer exchange.")
        }
    }

    /**
     * True once the runtime's torrent registry durably holds no descriptor at all for [torrent] -
     * i.e. the stopped session has been fully finalized and the same torrent can be registered
     * again. bt finalizes a session only when its processing chain runs to completion, and a chain
     * parked on peer discovery (a paused torrent with no peers, the common case) never gets there.
     * So after a short grace period for the natural path we fire the library's own teardown signal,
     * the synchronously-delivered TorrentStoppedEvent, which is what unregisters the torrent and
     * lets every per-torrent service (registry, block cache, peer sources, connection handlers)
     * release the session.
     *
     * One cleanup round is not enough: the registry's `register()` inserts the descriptor *before*
     * populating its data descriptor, on the dying session's executor thread - microseconds behind
     * the metadata event that triggered our cancellation. A lone quiet observation can therefore
     * catch a register still in flight, which lands its data descriptor after we returned. Each
     * round demands quiet across a hold window instead, and re-fires the teardown if the
     * descriptor surfaces late. Convergence is bounded by the round cap regardless.
     */
    private fun awaitTorrentSettled(runtime: BtRuntime, btClient: BtClient, torrent: Torrent?): Boolean {
        if (torrent == null) return true
        val registry = runCatching { runtime.service(TorrentRegistry::class.java) }.getOrNull() ?: return true
        val eventSink = runCatching { runtime.service(EventSink::class.java) }.getOrNull()
        val torrentId = torrent.torrentId
        repeat(SETTLE_ROUNDS) { round ->
            if (!awaitSettled(registry, torrentId, if (round == 0) NATURAL_SETTLE_MS else SETTLE_POLL_MS * 4)) {
                // Parked chain: force the library's own teardown, synchronously delivered.
                runCatching { eventSink?.fireTorrentStopped(torrentId) }
                    .onFailure { logger.warn("Could not fire the torrent-stopped cleanup event: {}", it.message) }
                if (!awaitSettled(registry, torrentId, SETTLE_TIMEOUT_MS)) return@repeat
            }
            // Hold window: an in-flight register needs a moment to populate the data descriptor;
            // quiet across the whole window means nothing re-registered after the teardown landed.
            Thread.sleep(SETTLE_HOLD_MS)
            if (!btClient.isStarted && isSettled(registry, torrentId)) {
                return true
            }
            runCatching { if (btClient.isStarted) btClient.stop() }
        }
        logger.warn(
            "Torrent session did not settle after {} cleanup rounds; an immediate resume may need one retry.",
            SETTLE_ROUNDS,
        )
        return false
    }

    private fun isSettled(registry: TorrentRegistry, torrentId: TorrentId): Boolean = runCatching {
        // `unregister` removes the map entry outright, so a finalized session leaves nothing
        // behind. Demanding full absence (not merely a null data descriptor) also refuses to
        // read a still-in-flight `register()` - which inserts the entry before populating it -
        // as settled.
        registry.getDescriptor(torrentId).isEmpty
    }.getOrDefault(true)

    private fun awaitSettled(registry: TorrentRegistry, torrentId: TorrentId, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (isSettled(registry, torrentId)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(SETTLE_POLL_MS)
        }
    }

    private fun reconstructTorrentFile(torrent: Torrent): ByteArray {
        val infoDictBytes = torrent.source.exchangedMetadata
        val infoDict = BEParser(infoDictBytes).readMap()
        val root = mutableMapOf<String, BEObject<*>>()
        root[MetadataConstants.INFOMAP_KEY] = infoDict
        torrent.announceKey.ifPresent { announceKey ->
            if (announceKey.isMultiKey) {
                val announceList =
                    announceKey.trackerUrls.map { tier -> BEList(tier.map { BEString(it) }) }
                root[MetadataConstants.ANNOUNCE_LIST_KEY] = BEList(announceList)
                announceKey.trackerUrls.firstOrNull()?.firstOrNull()
                    ?.let { root[MetadataConstants.ANNOUNCE_KEY] = BEString(it) }
            } else {
                root[MetadataConstants.ANNOUNCE_KEY] = BEString(announceKey.trackerUrl)
            }
        }
        val out = ByteArrayOutputStream()
        BEEncoder.encoder().encode(BEMap(root), out)
        return out.toByteArray()
    }

    /**
     * Builds the portable manifest that travels with a torrent. The load-bearing field is [bytes] —
     * the canonical `.torrent` (info dict + announce), which is exactly what `bt` needs to re-verify
     * and resume on another machine. The remaining fields are cheap, pre-parsed projections of the
     * same information used to size the import preview without re-decoding bencode here.
     */
    private fun torrentManifest(
        torrent: Torrent,
        bytes: ByteArray,
        selectedFileIndices: List<Int>? = null,
        completedPiecesHint: Int = 0,
    ): PortableManifest.Torrent {
        val trackers = buildList {
            torrent.announceKey.ifPresent { key ->
                if (key.isMultiKey) key.trackerUrls.forEach { tier -> addAll(tier) } else add(key.trackerUrl)
            }
        }
        val files = torrent.files.map {
            PortableTorrentFile(it.pathElements.joinToString("/"), it.size)
        }
        val pieceLength = torrent.chunkSize
        val pieceCount = if (pieceLength > 0) ((torrent.size + pieceLength - 1) / pieceLength).toInt() else 0
        // The hint only needs to be a truthful *count*; which bits are set is irrelevant because
        // every piece is re-verified against the embedded hashes on import.
        val hint = BitSet().apply {
            for (i in 0 until completedPiecesHint.coerceAtMost(pieceCount)) set(i)
        }
        return PortableManifest.Torrent(
            name = torrent.name,
            torrentFile = bytes,
            contentLength = torrent.size,
            pieceLength = pieceLength,
            pieceCount = pieceCount,
            trackers = trackers,
            files = files,
            completedPieces = hint,
            multiFile = torrent.files.size > 1,
            selectedFileIndices = selectedFileIndices
        )
    }
}

/** Monotonic milliseconds; never wall-clock, so an NTP correction cannot fake a timeout. */
private fun nowMs(): Long = System.nanoTime() / 1_000_000

/** Grace period for bt to finalize a stopped session on its own before the cleanup is forced.
 *  Deliberately tiny: the scheduler holds the global queue mutex while joining this teardown, so
 *  every millisecond here is pause/resume latency for *every* download. bt's natural finalization
 *  runs on seconds-scale (or never, for a parked chain), so in practice the forced event wins. */
private const val NATURAL_SETTLE_MS = 250L

/** Upper bound for confirming the forced teardown actually released the registration. */
private const val SETTLE_TIMEOUT_MS = 2_000L

/** Poll cadence for the settle wait; the forced teardown is synchronous, so this only catches it. */
private const val SETTLE_POLL_MS = 25L

/**
 * Cleanup rounds in [awaitTorrentSettled]. Each round either finds the registry quiet and
 * holding, or re-fires the teardown; a late register can only surface once the in-flight
 * `register()` completes, so one extra round suffices - 4 is generous headroom.
 */
private const val SETTLE_ROUNDS = 4

/** Quiet period the registry and client must hold for the teardown to count as final. */
private const val SETTLE_HOLD_MS = 100L

/** 1-second progress ticks between portable-marker hint refreshes (~every 30s of download). */
private const val MARKER_REFRESH_TICKS = 30