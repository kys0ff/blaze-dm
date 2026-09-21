package org.blaze.engine.network

import bt.Bt
import bt.bencoding.model.BEObject
import bt.bencoding.serializers.BEEncoder
import bt.bencoding.serializers.BEParser
import bt.bencoding.types.BEList
import bt.bencoding.types.BEMap
import bt.bencoding.types.BEString
import bt.data.file.FileSystemStorage
import bt.dht.DHTConfig
import bt.dht.DHTModule
import bt.metainfo.MetadataConstants
import bt.metainfo.Torrent
import bt.peerexchange.PeerExchangeModule
import bt.runtime.BtRuntime
import bt.runtime.Config
import bt.torrent.fileselector.FilePriority
import bt.tracker.http.HttpTrackerModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadFileMetadata
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.TorrentSource
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
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
import kotlin.time.toJavaDuration

/**
 * @param peerDiscoveryTimeout how long to wait for the first peer once metadata is known.
 * @param stallTimeout how long to tolerate zero downloaded bytes after peers have been seen.
 * @param metadataTimeout how long to wait for magnet metadata (BEP 9) before giving up.
 * @param maxPeerConnections upper bound on simultaneous peer connections (from settings).
 * @param enableSeeding keep uploading after all pieces are downloaded, up to [seedTimeLimitMinutes].
 * @param seedTimeLimitMinutes how long to keep seeding once the download completes.
 */
class TorrentNetworkClient(
    private val peerDiscoveryTimeout: Duration = 120.seconds,
    private val stallTimeout: Duration = 5.minutes,
    private val metadataTimeout: Duration = 3.minutes,
    private val maxPeerConnections: Int = 200,
    private val enableSeeding: Boolean = false,
    private val seedTimeLimitMinutes: Int = 30
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

            val destination = request.destination.toAbsolutePath().normalize()
            Files.createDirectories(destination)
            val storage = FileSystemStorage(destination)

            val runtime = buildRuntime()
            try {
                val monitor = Monitor(metadataPending = source is TorrentSource.Magnet)
                val totalSize = AtomicLong(-1L)
                val stopReason = AtomicReference<StopReason?>(null)
                val torrentRef = AtomicReference<Torrent?>(null)

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
                        monitor.onMetadataResolved()
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
                        trySend(
                            TorrentNetworkEvent.MetadataResolved(
                                torrent.name,
                                torrent.size,
                                bytes,
                                files
                            )
                        )
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
                }

                val btClient = clientBuilder.build()

                fun requestStop(reason: StopReason) {
                    if (stopReason.compareAndSet(null, reason)) btClient.stop()
                }

                val downloadMeter = RateMeter()
                val uploadMeter = RateMeter()
                val ticks = AtomicInteger(0)

                val future: CompletableFuture<*> = btClient.startAsync({ state ->
                    val peers = state.connectedPeers.size
                    monitor.onProgress(state.downloaded, peers)

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
                        logger.info(
                            "Torrent status: peers={}, down={} B/s, pieces={}/{}",
                            peers, downloadSpeed, state.piecesComplete, state.piecesTotal
                        )
                    }

                    if (state.piecesTotal > 0 && state.piecesRemaining == 0) {
                        // First tick where every piece is local. Without seeding we stop right away
                        // (previous behavior); with seeding we arm a deadline and keep uploading
                        // until the watchdog stops us via SEED_COMPLETE.
                        if (!monitor.piecesDone) {
                            monitor.piecesDone = true
                            if (enableSeeding && seedTimeLimitMinutes > 0) {
                                monitor.seedDeadlineMs = nowMs() + seedTimeLimitMinutes * 60_000L
                                logger.info(
                                    "Download complete; seeding for the next {} minute(s).",
                                    seedTimeLimitMinutes
                                )
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
                    runCatching { btClient.stop() }
                }
            } finally {
                runCatching { runtime.shutdown() }
                    .onFailure { logger.warn("Runtime shutdown failed", it) }
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

    private fun buildRuntime(): BtRuntime {
        val bindAddress = resolveBindAddress()
        logger.info("Torrent acceptor bound to {}", bindAddress)

        val config = Config().apply {
            acceptorAddress = bindAddress
            maxPeerConnections = this@TorrentNetworkClient.maxPeerConnections
            numOfHashingThreads = Runtime.getRuntime().availableProcessors()
            peerConnectionTimeout = 30.seconds.toJavaDuration()
            peerConnectionRetryCount = 3
            peerConnectionRetryInterval = 5.seconds.toJavaDuration()
            trackerTimeout = 20.seconds.toJavaDuration()
            trackerQueryInterval = 30.seconds.toJavaDuration()
        }

        val dhtModule = DHTModule(object : DHTConfig() {
            override fun shouldUseRouterBootstrap(): Boolean = true
        })

        return BtRuntime.builder(config)
            .module(dhtModule)
            .module(HttpTrackerModule())
            .module(PeerExchangeModule())
            .disableAutomaticShutdown()
            .build()
    }

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

        /** Monotonic-ms deadline for the seeding phase; 0 means not seeding. */
        @Volatile
        var seedDeadlineMs: Long = 0L

        @Volatile
        private var lastDownloaded: Long = 0L

        fun onMetadataResolved() {
            metadataPending = false
            phaseStartMs =
                nowMs() // peer-discovery clock starts once we actually know what to download
        }

        fun onProgress(downloaded: Long, peers: Int) {
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
            // Seeding: the download already finished, so the stall / no-peer timers no longer
            // apply. Only stop once the configured seed window elapses.
            piecesDone && seedDeadlineMs > 0 ->
                (StopReason.SEED_COMPLETE to seedTimeLimitMinutes.minutes).takeIf { now > seedDeadlineMs }

            metadataPending ->
                (StopReason.METADATA_TIMEOUT to metadataTimeout).takeIf { now - phaseStartMs > metadataTimeout.inWholeMilliseconds }

            !sawPeer ->
                (StopReason.NO_PEERS to peerDiscoveryTimeout).takeIf { now - phaseStartMs > peerDiscoveryTimeout.inWholeMilliseconds }

            else ->
                (StopReason.STALLED to stallTimeout).takeIf { now - lastDataMs > stallTimeout.inWholeMilliseconds }
        }
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
     * bt only ships an HTTP(S) tracker module here. udp:// and wss:// announce URLs (which is what most
     * public torrents, e.g. the WebTorrent Big Buck Bunny one, list) are silently unusable, so the swarm
     * then depends entirely on DHT/PEX. Logging it makes "0 peers" much easier to diagnose.
     */
    private fun logTrackers(torrent: Torrent) {
        val urls = buildList {
            torrent.announceKey.ifPresent { key ->
                if (key.isMultiKey) key.trackerUrls.forEach { addAll(it) } else add(key.trackerUrl)
            }
        }
        val usable = urls.filter { it.startsWith("http://") || it.startsWith("https://") }
        logger.info("Trackers: {} total, {} HTTP(S) usable: {}", urls.size, usable.size, urls)
        if (usable.isEmpty()) {
            logger.warn("No HTTP(S) tracker in torrent; peers can only come from DHT / peer exchange.")
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

    private fun InetAddress.isUsableIpv4(): Boolean =
        this is Inet4Address && !isLoopbackAddress && !isAnyLocalAddress && !isLinkLocalAddress

    private fun resolveBindAddress(): InetAddress {
        // 1) Ask the OS which local address routes to the internet (UDP connect sends no packets).
        runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 53)
                val local = socket.localAddress
                if (local.isUsableIpv4()) return local
            }
        }
        // 2) Offline / no default route: first sane physical-looking interface.
        val virtualPrefixes =
            listOf("docker", "veth", "br-", "virbr", "vboxnet", "vmnet", "zt", "lo")
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
}

private fun nowMs(): Long = System.nanoTime() / 1_000_000