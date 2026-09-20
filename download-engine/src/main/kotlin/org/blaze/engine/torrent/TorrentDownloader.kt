package org.blaze.engine.torrent

import bt.Bt
import bt.bencoding.serializers.BEEncoder
import bt.bencoding.serializers.BEParser
import bt.bencoding.types.BEList
import bt.bencoding.types.BEMap
import bt.bencoding.model.BEObject
import bt.bencoding.types.BEString
import bt.data.file.FileSystemStorage
import bt.dht.DHTConfig
import bt.dht.DHTModule
import bt.metainfo.MetadataConstants
import bt.metainfo.Torrent
import bt.peerexchange.PeerExchangeModule
import bt.runtime.BtClient
import bt.runtime.BtRuntime
import bt.runtime.Config
import bt.torrent.TorrentSessionState
import bt.tracker.http.HttpTrackerModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.core.Downloader
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class TorrentDownloader(
    private val request: DownloadRequest.Torrent,
    /** How long to wait for the *first* peer before giving up. */
    private val peerDiscoveryTimeout: Duration = 120.seconds,
    /** How long the download may make no progress (with peers connected) before giving up. */
    private val stallTimeout: Duration = 5.minutes,
    private val onMetadataResolved: ((ByteArray) -> Unit)? = null
) : Downloader {

    private val logger = LoggerFactory.getLogger(TorrentDownloader::class.java)

    /** Why the session was stopped, when we stopped it ourselves. */
    private enum class StopReason { DOWNLOADED, NO_PEERS, STALLED }

    // Stable identity and creation time for every DownloadTask this instance emits.
    private val downloadId = DownloadId(UUID.randomUUID().toString())
    private val createdAt = Instant.now()

    @Volatile private var client: BtClient? = null

    // Distinguishes a user-initiated stop (pause/cancel) from the session ending on its own.
    @Volatile private var stopRequested = false

    @Volatile private var initialPiecesComplete: Int = -1

    override fun download(): Flow<DownloadTask> = channelFlow {
        send(createInitialTask())

        try {
            val destination = request.destination.toAbsolutePath().normalize()
            Files.createDirectories(destination)
            require(Files.isDirectory(destination)) {
                "Torrent destination is not a directory: $destination"
            }

            logger.info("Starting torrent download")
            logger.info("Requested name: {}", request.name)
            logger.info("Destination: {}", destination)
            logger.info("Source: {}", request.torrentSource)

            val storage = FileSystemStorage(destination)

            // FIX #0 — the outbound bind address. This is the one that produces exactly
            // "metadata resolves, trackers answer, peers stays at 0, no errors".
            //
            // bt binds its peer sockets -- including *outgoing* ones -- to
            // Config.acceptorAddress, and the default is whatever the JVM/first suitable
            // interface happens to be. On a Linux dev box that is routinely wrong:
            //   - Debian/Ubuntu map the hostname to 127.0.1.1 in /etc/hosts, so
            //     InetAddress.getLocalHost() hands back a loopback address;
            //   - docker0 / virbr0 / vboxnet0 / vmnet / tun interfaces sort ahead of the
            //     real NIC in the enumeration.
            // Either way every connect() to a real peer leaves via an interface with no
            // route to the internet and fails instantly. The tracker and DHT sockets bind
            // separately to 0.0.0.0, so peer *discovery* keeps working -- which is why the
            // swarm looks empty rather than broken.
            val bindAddress = resolveBindAddress()
            logger.info("Binding peer connections to {}", bindAddress)
            logNetworkInterfaces()

            val config = Config().apply {
                acceptorAddress = bindAddress

                // 50 was low for a cold swarm: most candidates never complete a handshake,
                // so a small cap throttles discovery itself.
                maxPeerConnections = 200
                numOfHashingThreads = Runtime.getRuntime().availableProcessors()

                peerConnectionTimeout = 30.seconds.toJavaDuration()
                peerConnectionRetryCount = 3
                peerConnectionRetryInterval = 5.seconds.toJavaDuration()

                trackerTimeout = 20.seconds.toJavaDuration()
                trackerQueryInterval = 30.seconds.toJavaDuration()
            }

            // FIX #1 — peer discovery.
            //
            // The previous code combined autoLoadModules() with an explicitly configured
            // DHTModule. autoLoadModules() instantiates *every* module found on the classpath
            // "with its default configuration" -- including bt-dht's own DHTModule, whose
            // default has shouldUseRouterBootstrap() == false. Which of the two instances wins
            // is left to the runtime builder, so DHT could silently come up with no bootstrap
            // nodes at all: an empty routing table finds no peers, forever, with no error.
            //
            // Registering every module explicitly removes the ambiguity. It also makes the
            // HTTP tracker module a compile-time requirement rather than a silent no-op:
            // bt-core speaks only to UDP trackers, and http(s):// announce URLs are ignored
            // unless bt-http-tracker-client is on the classpath.
            val dhtModule = DHTModule(object : DHTConfig() {
                override fun shouldUseRouterBootstrap(): Boolean = true
            })

            val runtime = BtRuntime.builder(config)
                .module(dhtModule)
                .module(HttpTrackerModule())
                .module(PeerExchangeModule())
                .disableAutomaticShutdown()
                .build()

            try {
                val resolvedName = AtomicReference<String?>(null)
                val totalSize = AtomicLong(-1L)

                val clientBuilder = Bt.client(runtime)
                    .storage(storage)
                    .afterTorrentFetched { torrent ->
                        resolvedName.set(torrent.name)
                        totalSize.set(torrent.size)
                        logger.info("Metadata fetched: name={}, size={}, source={}", torrent.name, torrent.size, request.torrentSource)

                        val metadata = torrent.source.metadata
                        if (metadata.isPresent) {
                            logger.info("Providing metadata bytes to engine ({} bytes)", metadata.get().size)
                            onMetadataResolved?.invoke(metadata.get())
                        } else {
                            logger.info("Metadata bytes NOT available in TorrentSource, attempting to reconstruct BEP-3 torrent file")
                            try {
                                val bytes = reconstructTorrentFile(torrent)
                                logger.info("Providing reconstructed metadata bytes to engine ({} bytes)", bytes.size)
                                onMetadataResolved?.invoke(bytes)
                            } catch (e: Exception) {
                                logger.error("Failed to reconstruct torrent file", e)
                            }
                        }
                    }

                when (val source = request.torrentSource) {
                    is TorrentSource.File -> {
                        val file = File(source.path.toString())
                        if (!file.exists()) {
                            throw FileNotFoundException("Torrent file not found: ${file.absolutePath}")
                        }
                        if (file.length() < 10) { // Tiny files are definitely not valid torrents
                            throw IllegalArgumentException("Torrent file is too small or empty")
                        }
                        val url = source.path.toUri().toURL()
                        logger.info("Loading .torrent file: {}", file.absolutePath)
                        clientBuilder.torrent(url)
                    }

                    is TorrentSource.Magnet -> {
                        logger.info("Loading magnet URI: {}", source.uri)
                        clientBuilder.magnet(source.uri)
                    }
                }

                val btClient = clientBuilder.build()
                client = btClient
                logger.info("BtClient built successfully")

                // pause()/cancel() may have arrived while the runtime was still coming up.
                if (stopRequested) {
                    send(
                        mapStateToTask(
                            null,
                            isCancelled = true,
                            resolvedName = resolvedName.get(),
                            totalSize = totalSize.get()
                        )
                    )
                    return@channelFlow
                }

                val lastStateRef = AtomicReference<TorrentSessionState?>(null)
                val lastBytesRef = AtomicLong(0L)
                val lastTickRef = AtomicLong(System.nanoTime())

                val stopReason = AtomicReference<StopReason?>(null)
                val sawAnyPeer = AtomicBoolean(false)
                val lastProgressAt = AtomicLong(System.currentTimeMillis())

                logger.info("Starting BitTorrent session...")
                val future: CompletableFuture<*> = btClient.startAsync({ state ->
                    lastStateRef.set(state)

                    // Compute a rough instantaneous download speed from the byte delta
                    // between ticks, since the underlying library doesn't report one.
                    val now = System.nanoTime()
                    val previousTick = lastTickRef.getAndSet(now)
                    val previousBytes = lastBytesRef.getAndSet(state.downloaded)
                    val elapsedSeconds = (now - previousTick) / 1_000_000_000.0
                    val delta = state.downloaded - previousBytes
                    val speed = if (elapsedSeconds > 0) {
                        (delta / elapsedSeconds).toLong().coerceAtLeast(0L)
                    } else {
                        0L
                    }

                    val peers = state.connectedPeers.size
                    if (peers > 0) sawAnyPeer.set(true)
                    // "Progress" for watchdog purposes is either bytes arriving or the swarm
                    // growing; a connected-but-choked peer should not be treated as a stall.
                    if (delta > 0 || peers > 0) {
                        lastProgressAt.set(System.currentTimeMillis())
                    }

                    logger.debug(
                        "Tick: name={}, pieces={}/{}, remaining={}, downloaded={}/{}, peers={}, speed={} B/s",
                        resolvedName.get() ?: request.name,
                        state.piecesComplete, state.piecesTotal,
                        state.piecesRemaining,
                        state.downloaded, totalSize.get().takeIf { it > 0 } ?: "?",
                        peers,
                        speed
                    )

                    // trySend is non-suspending and thread-safe, so it can be called
                    // directly from bt's callback thread. This also preserves ordering,
                    // unlike launching a new coroutine per tick (the previous approach),
                    // which offered no ordering guarantee between concurrent launches.
                    trySend(
                        mapStateToTask(
                            state,
                            downloadSpeed = speed,
                            resolvedName = resolvedName.get(),
                            totalSize = totalSize.get()
                        )
                    )

                    // FIX #2 — the session future never completed on success.
                    //
                    // bt has no "stop when done" behaviour of its own: startAsync()'s future
                    // completes only when the client is stopped. Once the last piece arrived
                    // the client just kept seeding, awaitCompletion() never returned, and no
                    // Completed task was ever emitted. Stopping from the state listener is
                    // what bt's own CLI client does.
                    if (state.piecesTotal > 0 && state.piecesRemaining == 0) {
                        if (stopReason.compareAndSet(null, StopReason.DOWNLOADED)) {
                            logger.info("Download complete, stopping session")
                            btClient.stop()
                        }
                    }
                }, 1000)

                // FIX #3 — a swarm that never materializes is now an error, not a hang.
                // Previously a torrent with 0 peers sat at 0% indefinitely with no way out
                // except user cancellation.
                val watchdog = launch {
                    while (isActive) {
                        delay(2_000.milliseconds)
                        if (stopRequested || stopReason.get() != null) break

                        val idleMillis = System.currentTimeMillis() - lastProgressAt.get()
                        val limit = if (sawAnyPeer.get()) stallTimeout else peerDiscoveryTimeout
                        if (idleMillis > limit.inWholeMilliseconds) {
                            val reason = if (sawAnyPeer.get()) StopReason.STALLED else StopReason.NO_PEERS
                            if (stopReason.compareAndSet(null, reason)) {
                                logger.warn("Watchdog tripped: {} after {}ms idle", reason, idleMillis)
                                btClient.stop()
                            }
                            break
                        }
                    }
                }

                try {
                    future.awaitCompletion()

                    val finalState = lastStateRef.get()
                    logger.info(
                        "Session future completed: reason={}, stopRequested={}, finalState={}",
                        stopReason.get(), stopRequested, finalState
                    )

                    val completed = finalState != null &&
                            finalState.piecesTotal > 0 &&
                            finalState.piecesRemaining == 0

                    // FIX #4 — outcome classification.
                    //
                    // Every non-completion used to be reported as DownloadError.InvalidTorrent,
                    // including a user-initiated pause() (bt's stop() completes the future
                    // normally, so pausing was reported as a corrupt torrent) and an empty
                    // swarm. The torrent file itself is only ever the problem when it failed
                    // to parse -- which throws, and is handled below.
                    val finalTask = when {
                        stopRequested -> mapStateToTask(
                            finalState,
                            isCancelled = true,
                            resolvedName = resolvedName.get(),
                            totalSize = totalSize.get()
                        )

                        completed -> mapStateToTask(
                            finalState,
                            isCompleted = true,
                            resolvedName = resolvedName.get(),
                            totalSize = totalSize.get()
                        )

                        finalState == null || finalState.piecesTotal == 0 -> mapStateToTask(
                            finalState,
                            error = if (request.torrentSource is TorrentSource.File) {
                                DownloadError.InvalidTorrent
                            } else {
                                DownloadError.NetworkFailure(
                                    "Could not resolve torrent metadata (no peers responded)"
                                )
                            },
                            resolvedName = resolvedName.get(),
                            totalSize = totalSize.get()
                        )

                        stopReason.get() == StopReason.NO_PEERS -> mapStateToTask(
                            finalState,
                            error = DownloadError.NetworkFailure(
                                "No peers found after ${peerDiscoveryTimeout.inWholeSeconds}s — " +
                                        "the swarm may be empty or the trackers unreachable"
                            ),
                            resolvedName = resolvedName.get(),
                            totalSize = totalSize.get()
                        )

                        else -> mapStateToTask(
                            finalState,
                            error = DownloadError.NetworkFailure(
                                "Download stalled at ${finalState.piecesComplete}/${finalState.piecesTotal} pieces"
                            ),
                            resolvedName = resolvedName.get(),
                            totalSize = totalSize.get()
                        )
                    }
                    send(finalTask)
                } catch (e: CancellationException) {
                    if (stopRequested) {
                        send(
                            mapStateToTask(
                                lastStateRef.get(),
                                isCancelled = true,
                                resolvedName = resolvedName.get(),
                                totalSize = totalSize.get()
                            )
                        )
                    } else {
                        // Genuine cancellation of the flow's collector: clean up and propagate,
                        // rather than swallowing it as if it were a normal outcome.
                        throw e
                    }
                } catch (e: Exception) {
                    logger.error("Torrent session failed", e)
                    send(
                        mapStateToTask(
                            lastStateRef.get(),
                            error = DownloadError.NetworkFailure(e.message ?: "Torrent error"),
                            resolvedName = resolvedName.get(),
                            totalSize = totalSize.get()
                        )
                    )
                } finally {
                    watchdog.cancel()
                    logger.info("Stopping BitTorrent client")
                    btClient.stop()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to initialize torrent runtime", e)
                send(mapStateToTask(null, error = DownloadError.InvalidTorrent))
            } finally {
                logger.info("Shutting down BitTorrent runtime")
                runtime.shutdown()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Download setup failed", e)
            send(
                mapStateToTask(
                    null,
                    error = DownloadError.InvalidTorrent
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Suspends until this future completes, cancellable by the calling coroutine.
     * Replaces a blocking `future.join()`, which ignored coroutine cancellation
     * until the future happened to finish on its own.
     */
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

    /**
     * Picks the local IPv4 address that actually has a route to the internet, rather than
     * letting bt guess. Never sends a packet: connecting a UDP socket only makes the kernel
     * run its routing table and pick a source address.
     */
    private fun reconstructTorrentFile(torrent: Torrent): ByteArray {
        val infoDictBytes = torrent.source.exchangedMetadata
        val infoDict = BEParser(infoDictBytes).readMap()

        val root = mutableMapOf<String, BEObject<*>>()
        root[MetadataConstants.INFOMAP_KEY] = infoDict

        torrent.announceKey.ifPresent { announceKey ->
            if (announceKey.isMultiKey) {
                val announceList = announceKey.trackerUrls.map { tier ->
                    BEList(tier.map { BEString(it) })
                }
                root[MetadataConstants.ANNOUNCE_LIST_KEY] = BEList(announceList)

                // BEP-3 says 'announce' should be a single string.
                // We pick the first tracker from the first tier.
                announceKey.trackerUrls.firstOrNull()?.firstOrNull()?.let {
                    root[MetadataConstants.ANNOUNCE_KEY] = BEString(it)
                }
            } else {
                root[MetadataConstants.ANNOUNCE_KEY] = BEString(announceKey.trackerUrl)
            }
        }

        val rootMap = BEMap(root)
        val out = ByteArrayOutputStream()
        BEEncoder.encoder().encode(rootMap, out)
        return out.toByteArray()
    }

    private fun resolveBindAddress(): InetAddress {
        runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 53)
                val local = socket.localAddress
                if (local is Inet4Address && !local.isLoopbackAddress && !local.isAnyLocalAddress) {
                    return local
                }
            }
        }.onFailure {
            logger.warn("Route probe failed: {}", it.message)
        }

        // Fallback: first up, non-loopback, non-virtual interface with an IPv4 address.
        val virtualPrefixes = listOf("docker", "veth", "br-", "virbr", "vboxnet", "vmnet", "zt", "lo")
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filterNot { iface -> virtualPrefixes.any { iface.name.startsWith(it) } }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.let { return it }
        }

        logger.warn("No usable interface found, falling back to 0.0.0.0")
        return InetAddress.getByName("0.0.0.0")
    }

    private fun logNetworkInterfaces() {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                val addresses = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .joinToString(", ") { it.hostAddress }
                if (addresses.isNotEmpty()) {
                    logger.debug("Interface {}: {} (up={})", iface.name, addresses, iface.isUp)
                }
            }
        }
    }

    private fun createInitialTask(): DownloadTask {
        return DownloadTask(
            id = downloadId,
            name = request.name,
            request = request,
            state = DownloadState.Starting,
            totalBytes = null,
            downloadedBytes = 0,
            downloadSpeed = 0,
            uploadSpeed = 0,
            progress = 0f,
            createdAt = createdAt
        )
    }

    private fun mapStateToTask(
        state: TorrentSessionState?,
        isCompleted: Boolean = false,
        isCancelled: Boolean = false,
        error: DownloadError? = null,
        downloadSpeed: Long = 0L,
        resolvedName: String? = null,
        totalSize: Long = -1L
    ): DownloadTask {
        val piecesTotal = state?.piecesTotal ?: 0
        val piecesComplete = state?.piecesComplete ?: 0

        if (state != null && piecesTotal > 0 && state.downloaded == 0L) {
            // During hashing or before first byte arrives, we keep updating initialPiecesComplete
            // to reflect what we already have on disk.
            initialPiecesComplete = piecesComplete
        }

        val downloaded = when {
            isCompleted || (state != null && piecesTotal > 0 && state.piecesRemaining == 0) -> {
                if (totalSize > 0) totalSize else state?.downloaded ?: 0L
            }
            state != null && piecesTotal > 0 && totalSize > 0 -> {
                if (state.downloaded == 0L) {
                    // If no bytes downloaded this session, trust the piece count (covers hashing/resuming)
                    (piecesComplete.toDouble() / piecesTotal * totalSize).toLong()
                } else if (initialPiecesComplete >= 0) {
                    // Bytes downloaded this session + what we had at the start
                    val initialBytes = (initialPiecesComplete.toDouble() / piecesTotal * totalSize).toLong()
                    (initialBytes + state.downloaded).coerceIn(state.downloaded, totalSize)
                } else {
                    state.downloaded
                }
            }
            else -> state?.downloaded ?: 0L
        }

        val progress = if (piecesTotal > 0) piecesComplete.toFloat() / piecesTotal else 0f

        val downloadState = when {
            error != null -> DownloadState.Failed
            isCancelled -> DownloadState.Cancelled
            isCompleted -> DownloadState.Completed
            state == null -> DownloadState.Starting
            piecesTotal == 0 -> DownloadState.ResolvingMetadata
            state.piecesRemaining > 0 -> DownloadState.Downloading
            else -> DownloadState.Seeding
        }

        return DownloadTask(
            id = downloadId,
            name = resolvedName ?: request.name,
            request = request,
            state = downloadState,
            totalBytes = if (totalSize > 0) totalSize else null,
            downloadedBytes = downloaded,
            downloadSpeed = downloadSpeed,
            uploadSpeed = 0, // TODO: populate once bt exposes uploaded-byte tracking for speed calc
            peers = state?.connectedPeers?.size ?: 0,
            progress = progress,
            error = error,
            createdAt = createdAt,
            completedAt = if (isCompleted) Instant.now() else null
        )
    }

    override suspend fun pause() {
        // Note: bt only exposes stop(), not a real pause/resume distinction, so this
        // currently behaves the same as cancel() from the engine's point of view.
        stopRequested = true
        client?.stop()
    }

    override suspend fun cancel() {
        stopRequested = true
        client?.stop()
    }
}