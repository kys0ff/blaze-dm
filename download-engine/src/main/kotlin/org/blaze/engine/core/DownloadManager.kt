package org.blaze.engine.core

import bt.bencoding.serializers.BEParser
import bt.metainfo.MetadataConstants
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.blaze.engine.api.*
import org.blaze.engine.archive.HttpRemoteByteSource
import org.blaze.engine.archive.ZipCentralDirectory
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.execution.DownloadExecutorImpl
import org.blaze.engine.hls.HlsDownloader
import org.blaze.engine.hls.HlsPlaylistException
import org.blaze.engine.hls.HlsProtection
import org.blaze.engine.metrics.EngineMetrics
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.persistence.DownloadRecord
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.portable.*
import org.blaze.engine.retry.DefaultRetryPolicy
import org.blaze.engine.retry.RetryPolicy
import org.blaze.engine.scheduler.DownloadScheduler
import org.blaze.engine.settings.DEFAULT_USER_AGENT
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.engine.storage.DefaultFileStorage
import org.blaze.engine.storage.FileStorage
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class DownloadManager(
    private val scope: CoroutineScope,
    private val repository: DownloadRepository,
    private val settingsRepository: EngineSettingsRepository,
    private val storage: FileStorage = DefaultFileStorage(),
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val executorFactory: ((DownloadTask) -> DownloadExecutor)? = null
) : DownloadEngine {

    private val logger = LoggerFactory.getLogger(DownloadManager::class.java)

    private val persistSignal = Channel<Unit>(Channel.CONFLATED)
    private val tasks = MutableStateFlow<Map<DownloadId, DownloadTask>>(emptyMap())

    /** One throttle for the whole engine, so "global" limit really is global. */
    private val limiter = BandwidthLimiter()
    private val metrics = EngineMetrics()

    private val scheduler = DownloadScheduler(
        scope = scope,
        settingsRepository = settingsRepository,
        executorFactory = { task ->
            executorFactory?.invoke(task) ?: DownloadExecutorImpl(
                initialTask = task,
                httpClient = httpClient,
                storage = storage,
                settingsRepository = settingsRepository,
                retryPolicy = DefaultRetryPolicy(settingsRepository.settings.value),
                onMetadataResolved = { id, bytes -> cacheMetadata(id, bytes) },
                limiter = limiter,
                metrics = metrics
            )
        },
        onTaskUpdated = { task ->
            tasks.update { it + (task.id to task) }
            persist()
            if (task.state == DownloadState.Completed) {
                cleanupMetadata(task.id, task.request)
            }
        }
    )

    init {
        scope.launch(Dispatchers.IO) {
            try {
                storage.ensureDirectory(repository.storageDir.resolve("cache"))
            } catch (e: Exception) {
                logger.error("Failed to create cache directory", e)
            }
            while (true) {
                if (persistSignal.receiveCatching().isClosed) break
                delay(PERSIST_DEBOUNCE)
                while (persistSignal.tryReceive().isSuccess) { /* conflated: nothing to drain */
                }
                try {
                    writeSnapshot()
                } catch (e: Exception) {
                    logger.error("Failed to persist the download list", e)
                }
            }
        }
        scope.launch {
            settingsRepository.settings
                .map { settings ->
                    when {
                        settings.snailModeEnabled -> settings.snailSpeedKbps * 1024
                        settings.globalSpeedLimitEnabled -> settings.globalSpeedLimitKbps * 1024
                        else -> 0L
                    }
                }
                .distinctUntilChanged()
                .collect(limiter::setLimit)
        }
        loadTasks()
    }

    /** Engine-wide download ceiling in bytes/s; 0 when throttling is off. */
    val speedLimitBytesPerSec: Long get() = limiter.limitBytesPerSecond

    companion object {
        /** Upper bound on how often the whole download list is re-serialized to disk. */
        private val PERSIST_DEBOUNCE = 1.seconds

        /** How long to wait for a `.torrent` metainfo file to arrive over HTTP before giving up. */
        private val TORRENT_FILE_TIMEOUT = 30.seconds

        /**
         * A `.torrent` is a tiny descriptor, so anything larger than this is not metainfo but a body
         * mis-served under a torrent URL; refuse it rather than buffer a runaway download in memory.
         */
        private const val TORRENT_FILE_MAX_BYTES = 50L * 1024 * 1024

        /**
         * The production HTTP client.
         *
         * OkHttp is the engine chosen by `HttpEngineBenchmark` (see `docs/PROGRESS.md` Phase 3
         * sect.1). On a 32 MiB segmented loopback workload it wins every unthrottled cell against
         * the JDK client (1.2-2.1x throughput), matches or beats it under a per-connection cap,
         * and does it with roughly 3x fewer threads at conn=16 and half the GC pause. CIO was
         * rejected because it opens a fresh TCP connection per request (serverConnections=34 at
         * conn=4), so a segmented download would pay a full TCP - and on a real host, TLS -
         * handshake per chunk and leave the abandoned sockets in TIME_WAIT.
         *
         * Like the JDK engine, OkHttp pools and reuses keep-alive connections and speaks HTTP/2,
         * which is what the segmented path assumes.
         *
         * `socketTimeoutMillis` on the Ktor `HttpTimeout` plugin translates to OkHttp's own
         * read/write timeout. A stalled transfer is additionally bounded by the segmented path's
         * watchdog (see `HttpDownloadCoordinator`), so the timeout here is a coarse backstop for
         * dead hosts, not the primary stall detector.
         */
        fun createDefaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
            followRedirects = false
        }
    }

    private fun loadTasks() {
        scope.launch(Dispatchers.IO) {
            val records = try {
                repository.loadAll()
            } catch (e: Exception) {
                logger.error("Failed to load tasks", e)
                emptyList()
            }

            val settings = settingsRepository.settings.value
            val loadedTasks = records.mapNotNull { record ->
                try {
                    val task = parseRecord(record)
                    val finalState = when {
                        task.state.isActive || task.state == DownloadState.Paused || task.state == DownloadState.Failed -> {
                            if (settings.resumeDownloadsOnStartup) DownloadState.Queued else DownloadState.Paused
                        }

                        task.state == DownloadState.Queued -> {
                            if (settings.startQueuedOnStartup) DownloadState.Queued else DownloadState.Paused
                        }

                        else -> task.state
                    }
                    task.copy(state = finalState)
                } catch (e: Exception) {
                    logger.warn("Skipping corrupt record ${record.id}", e)
                    null
                }
            }.associateBy { it.id }

            tasks.update { current -> loadedTasks + current }
            scheduler.updateTasks(tasks.value)
        }
    }

    private fun parseRecord(record: DownloadRecord): DownloadTask {
        val request = when (record.type) {
            "HTTP" -> DownloadRequest.Http(
                record.name,
                record.url ?: error("HTTP record ${record.id} missing url"),
                Path.of(record.destination)
            )

            "TORRENT" -> {
                val origin = when {
                    record.magnetUri != null -> TorrentSource.Magnet(record.magnetUri)
                    record.torrentUrl != null -> TorrentSource.Url(record.torrentUrl)
                    else -> null
                }
                val cachedFile = record.torrentPath?.let { Path.of(it) }
                val source: TorrentSource = when {
                    cachedFile != null && Files.isRegularFile(cachedFile) -> TorrentSource.File(
                        cachedFile,
                        origin
                    )

                    origin != null -> origin
                    cachedFile != null -> TorrentSource.File(cachedFile)
                    else -> error("Torrent record ${record.id} missing every torrent source field")
                }
                DownloadRequest.Torrent(
                    record.name,
                    source,
                    Path.of(record.destination),
                    record.fileIndices
                )
            }

            "HLS" -> DownloadRequest.Hls(
                name = record.name,
                playlistUrl = record.url ?: error("HLS record ${record.id} missing url"),
                destination = Path.of(record.destination),
                variantIndex = record.variantIndex ?: -1,
                drmAcknowledged = record.drmAcknowledged,
            )

            "ARCHIVE" -> DownloadRequest.Archive(
                name = record.name,
                url = record.url ?: error("ARCHIVE record ${record.id} missing url"),
                destination = Path.of(record.destination),
                selectedEntries = record.selectedEntries,
            )

            else -> error("Unknown download type '${record.type}' for record ${record.id}")
        }

        return DownloadTask(
            id = DownloadId(record.id),
            name = record.name,
            request = request,
            state = DownloadState.fromString(record.state),
            totalBytes = record.totalBytes,
            downloadedBytes = record.downloadedBytes,
            downloadSpeed = 0,
            retryCount = record.retryCount,
            createdAt = Instant.ofEpochMilli(record.addedAt),
            scheduledAt = record.scheduledAt?.let { Instant.ofEpochMilli(it) },
            files = record.files,
            priority = DownloadPriority.fromString(record.priority),
            resumeSupported = record.resumeSupported
        )
    }

    private fun serializeState(state: DownloadState): String =
        DownloadState.toString(state)

    private fun persist() {
        persistSignal.trySend(Unit)
    }

    private suspend fun writeSnapshot() {
        val currentTasks = tasks.value.values.toList()
        val records = currentTasks.map { task ->
            val torrentSource = (task.request as? DownloadRequest.Torrent)?.torrentSource
            val effectiveOrigin = (torrentSource as? TorrentSource.File)?.origin ?: torrentSource

            DownloadRecord(
                id = task.id.value,
                name = task.name,
                type = when (task.request) {
                    is DownloadRequest.Http -> "HTTP"
                    is DownloadRequest.Torrent -> "TORRENT"
                    is DownloadRequest.Hls -> "HLS"
                    is DownloadRequest.Archive -> "ARCHIVE"
                },
                url = when (task.request) {
                    is DownloadRequest.Http -> task.request.url
                    is DownloadRequest.Hls -> task.request.playlistUrl
                    is DownloadRequest.Archive -> task.request.url
                    else -> null
                },
                torrentPath = (torrentSource as? TorrentSource.File)?.path?.toString(),
                magnetUri = (effectiveOrigin as? TorrentSource.Magnet)?.uri,
                torrentUrl = (effectiveOrigin as? TorrentSource.Url)?.url,
                destination = task.request.destination.toString(),
                state = serializeState(task.state),
                totalBytes = task.totalBytes,
                downloadedBytes = task.downloadedBytes,
                addedAt = task.createdAt.toEpochMilli(),
                scheduledAt = task.scheduledAt?.toEpochMilli(),
                retryCount = task.retryCount,
                fileIndices = (task.request as? DownloadRequest.Torrent)?.fileIndices,
                files = task.files,
                priority = task.priority.name,
                resumeSupported = task.resumeSupported,
                variantIndex = (task.request as? DownloadRequest.Hls)?.variantIndex,
                drmAcknowledged = (task.request as? DownloadRequest.Hls)?.drmAcknowledged ?: false,
                selectedEntries = (task.request as? DownloadRequest.Archive)?.selectedEntries
            )
        }
        withContext(Dispatchers.IO) {
            repository.saveAll(records)
        }
    }

    private fun cacheMetadata(id: DownloadId, bytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            val task = tasks.value[id] ?: return@launch
            val request = task.request as? DownloadRequest.Torrent ?: return@launch

            if (request.torrentSource is TorrentSource.File) {
                val path = request.torrentSource.path
                if (path.startsWith(repository.storageDir.resolve("cache"))) return@launch
            }

            try {
                val cacheDir = repository.storageDir.resolve("cache")
                storage.ensureDirectory(cacheDir)
                // Key the cached metainfo by the same source-hash the preview path uses, so preview and the
                // real download share ONE entry (the old per-id key orphaned every sha256-keyed preview file).
                val torrentFile = cacheDir.resolve("${getCacheKey(request)}.torrent")
                Files.write(torrentFile, bytes)

                // Preserve the user-facing locator: a magnet/url stays itself; an already-swapped File keeps
                // the origin it was cached from, so the cache key and the shown source both stay stable.
                val origin = when (val src = request.torrentSource) {
                    is TorrentSource.Magnet, is TorrentSource.Url -> src
                    is TorrentSource.File -> src.origin
                }
                val newRequest =
                    request.copy(torrentSource = TorrentSource.File(torrentFile, origin))
                val updatedTask = task.copy(request = newRequest)
                tasks.update { it + (id to updatedTask) }
                persist()
            } catch (e: Exception) {
                logger.error("Failed to cache metadata", e)
            }
        }
    }

    private fun getCacheKey(request: DownloadRequest): String {
        val sourceString = when (request) {
            is DownloadRequest.Http -> request.url
            is DownloadRequest.Hls -> request.playlistUrl
            is DownloadRequest.Archive -> request.url
            is DownloadRequest.Torrent -> torrentSourceKeyString(request.torrentSource)
        }
        return sha256(sourceString)
    }

    /**
     * The user-facing locator whose hash names a torrent's cache entry. A magnet/url is itself; a `.torrent`
     * file resolves back through the [TorrentSource.File.origin] it was cached from, so the same source keeps
     * one key whether it is seen as a magnet or as its cached file - and only an origin-less file (an import)
     * falls back to its path.
     */
    private fun torrentSourceKeyString(source: TorrentSource): String = when (source) {
        is TorrentSource.Magnet -> source.uri
        is TorrentSource.Url -> source.url
        is TorrentSource.File -> source.origin?.let { torrentSourceKeyString(it) }
            ?: source.path.toAbsolutePath().toString()
    }

    /** A stable, filesystem-safe cache identity for a source string: its lowercase SHA-256 hex. */
    private fun sha256(sourceString: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(sourceString.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun cleanupMetadata(id: DownloadId, request: DownloadRequest?) {
        scope.launch(Dispatchers.IO) {
            try {
                val cacheDir = repository.storageDir.resolve("cache")
                // Legacy per-id file (written before the keys were unified) belongs to this download alone.
                storage.delete(cacheDir.resolve("${id.value}.torrent"))
                val torrent = request as? DownloadRequest.Torrent ?: return@launch
                val key = getCacheKey(torrent)
                // The source-hash file is shared by every download of the same source (and is the file the
                // preview path created). Drop it only when no other task still needs it: a not-yet-completed
                // sibling (queued/paused/failed/active) can still resume and would have to re-resolve the
                // swarm without it, while a completed one has already run its own cleanup and needs nothing.
                val stillReferenced = tasks.value.values.any { other ->
                    other.id != id &&
                        other.request is DownloadRequest.Torrent &&
                        other.state != DownloadState.Completed &&
                        getCacheKey(other.request as DownloadRequest.Torrent) == key
                }
                if (!stillReferenced) storage.delete(cacheDir.resolve("$key.torrent"))
            } catch (e: Exception) {
                logger.error("Failed to cleanup metadata", e)
            }
        }
    }

    override suspend fun fetchMetadata(request: DownloadRequest): DownloadMetadata? =
        withContext(Dispatchers.IO) {
            if (request is DownloadRequest.Http) {
                val client = HttpNetworkClient(
                    client = httpClient,
                    userAgent = settingsRepository.settings.value.httpUserAgent.ifBlank { DEFAULT_USER_AGENT },
                    maxRedirects = settingsRepository.settings.value.maxRedirects
                )
                return@withContext runCatching { client.probe(request) }.getOrNull()?.let { probe ->
                    DownloadMetadata(
                        name = request.name,
                        totalSize = probe.totalBytes.takeIf { it > 0 },
                        files = null,
                        resumeSupported = probe.rangeSupport
                    )
                }
            }

            val tempId = DownloadId.generate()
            val tempTask =
                DownloadTask(tempId, request.name, request, DownloadState.Starting, null, 0, 0)

            val executor = DownloadExecutorImpl(
                initialTask = tempTask,
                httpClient = httpClient,
                storage = storage,
                settingsRepository = settingsRepository,
                retryPolicy = object : RetryPolicy {
                    override fun getNextDelay(error: DownloadError, retryCount: Int): Long? = null
                },
                onMetadataResolved = { _, bytes ->
                    try {
                        val cacheDir = repository.storageDir.resolve("cache")
                        Files.createDirectories(cacheDir)
                        val key = getCacheKey(request)
                        val torrentFile = cacheDir.resolve("$key.torrent")
                        Files.write(torrentFile, bytes)
                    } catch (_: Exception) {
                    }
                }
            )

            val metadata = withTimeoutOrNull(30.seconds) {
                executor.execute().firstOrNull { it.totalBytes != null && it.totalBytes > 0 }
            }

            metadata?.let {
                DownloadMetadata(
                    it.name,
                    it.totalBytes,
                    it.files,
                    resumeSupported = true
                )
            }
        }

    override suspend fun inspectHls(
        url: String,
        variantIndex: Int,
        headers: Map<String, String>,
    ): HlsInspection = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.value
        val client = HttpNetworkClient(
            client = httpClient,
            userAgent = settings.httpUserAgent.ifBlank { DEFAULT_USER_AGENT },
            maxRedirects = settings.maxRedirects,
        )
        val downloader = HlsDownloader(client, storage, limiter)
        val inspection = try {
            downloader.inspect(url, variantIndex, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val notAStream = e is HlsPlaylistException
            return@withContext HlsInspection(
                status = if (notAStream) HlsInspectStatus.NotAStream else HlsInspectStatus.Error,
                protection = HlsProtectionLevel.None,
                variants = emptyList(),
                selectedVariantIndex = -1,
                estimatedTotalBytes = null,
                message = e.message ?: "could not read the stream",
            )
        }
        val media = inspection.playlist
        val protection = when (media.protection) {
            HlsProtection.None -> HlsProtectionLevel.None
            HlsProtection.OrdinaryEncryption -> HlsProtectionLevel.OrdinaryEncryption
            HlsProtection.DrmSuspected -> HlsProtectionLevel.DrmSuspected
            HlsProtection.Uncertain -> HlsProtectionLevel.Uncertain
        }
        val needsConsent = media.protection == HlsProtection.DrmSuspected || media.protection == HlsProtection.Uncertain
        val status = when {
            !media.isPlayableMedia -> HlsInspectStatus.NotAStream
            needsConsent -> HlsInspectStatus.NeedsDrmConsent
            media.isLive -> HlsInspectStatus.LiveUnsupported
            else -> HlsInspectStatus.Playable
        }
        val message = when (status) {
            HlsInspectStatus.NeedsDrmConsent ->
                "This stream appears to be content-protected (DRM). Blaze will not bypass DRM or extract " +
                        "protected keys; continuing only downloads what is legitimately accessible."

            HlsInspectStatus.LiveUnsupported ->
                "This is a live/event stream; only finished (VOD) streams can be saved to a file."

            HlsInspectStatus.NotAStream -> "This URL is not a stream Blaze can parse."
            else -> null
        }
        val selectedBandwidth =
            inspection.variants.firstOrNull { it.index == inspection.selectedVariantIndex }?.bandwidth
                ?: inspection.variants.maxByOrNull { it.bandwidth ?: -1L }?.bandwidth
        val durationSeconds = media.segments.sumOf { it.durationSeconds }
        val estimated = if (selectedBandwidth != null && selectedBandwidth > 0 && durationSeconds > 0) {
            (selectedBandwidth.toDouble() / 8.0 * durationSeconds).toLong()
        } else null
        HlsInspection(
            status = status,
            protection = protection,
            variants = inspection.variants.map {
                HlsVariantInfo(it.index, it.bandwidth, it.resolution, it.codecs, it.language, it.name)
            },
            selectedVariantIndex = inspection.selectedVariantIndex,
            estimatedTotalBytes = estimated,
            message = message,
        )
    }

    override suspend fun inspectArchive(
        url: String,
        headers: Map<String, String>,
    ): ArchiveInspection = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.value
        val client = HttpNetworkClient(
            client = httpClient,
            userAgent = settings.httpUserAgent.ifBlank { DEFAULT_USER_AGENT },
            maxRedirects = settings.maxRedirects,
        )
        val source = HttpRemoteByteSource(client, url, headers)
        try {
            val listing = ZipCentralDirectory.inspect(source)
            if (!source.rangeSupported) {
                return@withContext ArchiveInspection.FullDownloadOnly(
                    listing.reasonIfUnsupported ?: "this server does not support partial (byte-range) reads"
                )
            }
            ArchiveInspection.Browsable(
                entries = listing.entries.map {
                    ArchiveEntryInfo(it.name, it.isDirectory, it.compressedSize, it.uncompressedSize, it.extractable)
                },
                totalUncompressedBytes = listing.totalUncompressedBytes,
                selectiveSupported = listing.selectiveSupported,
                reasonIfUnsupported = listing.reasonIfUnsupported,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ArchiveInspection.Unavailable(e.message ?: "could not read the archive")
        }
    }

    /**
     * Fetches the `.torrent` served at [url] over HTTP(S), validates it is metainfo, caches it under the
     * source's hash and parses it into a preview. The caller starts the download from the returned cached
     * file, so it behaves exactly like a torrent the user picked from disk rather than a URL the swarm has
     * to resolve again.
     */
    override suspend fun fetchTorrentFile(
        url: String,
        credentials: Map<String, String>
    ): TorrentFilePreview? =
        withContext(Dispatchers.IO) {
            val trimmed = url.trim()
            if (!(trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith(
                    "https://",
                    ignoreCase = true
                ))
            ) {
                return@withContext null
            }

            val client = HttpNetworkClient(
                client = httpClient,
                userAgent = settingsRepository.settings.value.httpUserAgent.ifBlank { DEFAULT_USER_AGENT },
                maxRedirects = settingsRepository.settings.value.maxRedirects
            )
            val httpRequest = DownloadRequest.Http(
                name = trimmed.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "download.torrent" },
                url = trimmed,
                destination = repository.storageDir.resolve("cache"),
                headers = credentials,
            )
            val out = ByteArrayOutputStream()
            val streamResult = withTimeoutOrNull(TORRENT_FILE_TIMEOUT) {
                runCatching {
                    client.stream(httpRequest) { bytes, len ->
                        if (out.size().toLong() + len > TORRENT_FILE_MAX_BYTES) {
                            throw java.io.IOException("torrent metainfo exceeds ${TORRENT_FILE_MAX_BYTES / (1024 * 1024)} MiB")
                        }
                        out.write(bytes, 0, len)
                    }
                }.getOrNull()
            }
            if (streamResult !is HttpNetworkClient.StreamResult.Success || streamResult.received <= 0) {
                return@withContext null
            }

            val bytes = out.toByteArray()
            if (!looksLikeTorrentBytes(bytes)) return@withContext null

            try {
                val cacheDir = repository.storageDir.resolve("cache")
                storage.ensureDirectory(cacheDir)
                val torrentFile = cacheDir.resolve("${sha256(trimmed)}.torrent")
                Files.write(torrentFile, bytes)

                val metadata = fetchMetadata(
                    DownloadRequest.Torrent(
                        name = torrentFile.fileName.toString().removeSuffix(".torrent"),
                        torrentSource = TorrentSource.File(torrentFile),
                        destination = cacheDir,
                    )
                ) ?: return@withContext null

                TorrentFilePreview(torrentFile, metadata)
            } catch (e: Exception) {
                logger.warn("Failed to prepare torrent from {}", trimmed, e)
                null
            }
        }

    /** True when [bytes] bencode-decode to a map carrying an `info` dictionary, i.e. real torrent metainfo. */
    private fun looksLikeTorrentBytes(bytes: ByteArray): Boolean = runCatching {
        BEParser(bytes).readMap().value.containsKey(MetadataConstants.INFOMAP_KEY)
    }.getOrDefault(false)

    override suspend fun detectPortableDownload(path: Path): PortableDownloadInfo? =
        withContext(Dispatchers.IO) { runCatching { PortableImport.detect(path) }.getOrNull() }

    override suspend fun importPortableDownload(artifact: Path, destination: Path): DownloadId? =
        withContext(Dispatchers.IO) {
            val info = PortableImport.detect(artifact) ?: return@withContext null
            val id = when (info.kind) {
                PortableTransferKind.HTTP -> importHttp(artifact, destination, info)
                PortableTransferKind.TORRENT -> importTorrent(artifact)
            }
            if (id != null) scheduler.start(id)
            id
        }

    private suspend fun importHttp(
        artifact: Path,
        destination: Path,
        info: PortableDownloadInfo
    ): DownloadId? {
        val finalFile = destination.resolve(info.suggestedName)
        val partial = storage.getPartialFile(finalFile).toPath()

        if (runCatching { artifact.toRealPath() }.getOrNull() != runCatching { partial.toRealPath() }.getOrNull()) {
            runCatching { Files.createDirectories(partial.parent) }
            val moved = runCatching {
                Files.move(
                    artifact,
                    partial,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.isSuccess
            if (!moved) runCatching {
                Files.copy(
                    artifact,
                    partial,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
                .onFailure { return null }
        }
        val restored = inspectPortableFile(partial) ?: return null
        val url = (restored.manifest as? PortableManifest.Http)?.originalUrl ?: return null
        installHttpResumeSidecar(restored, storage.getResumeStateFile(finalFile).toPath())
        return enqueue(
            DownloadRequest.Http(name = info.suggestedName, url = url, destination = finalFile),
            totalBytes = restored.contentLength
        )
    }

    private suspend fun importTorrent(artifact: Path): DownloadId? {
        val dir = PortableImport.resolveTorrentDestination(artifact) ?: return null
        val record = TorrentPortableFile.read(TorrentPortableFile.markerPath(dir)) ?: return null

        if (record.manifest.torrentFile.isEmpty()) return null

        return try {
            val cacheDir = repository.storageDir.resolve("cache")
            storage.ensureDirectory(cacheDir)
            val torrentFile = cacheDir.resolve("import-${System.nanoTime()}.torrent")
            withContext(Dispatchers.IO) { Files.write(torrentFile, record.manifest.torrentFile) }
            val files = record.manifest.files.map { DownloadFileMetadata(it.relativePath, it.size) }
            enqueue(
                DownloadRequest.Torrent(
                    name = record.manifest.name,
                    torrentSource = TorrentSource.File(torrentFile),
                    destination = dir,
                    fileIndices = record.manifest.selectedFileIndices,
                ),
                totalBytes = record.manifest.contentLength,
                files = files
            )
        } catch (e: Exception) {
            logger.error("Failed to import portable torrent from {}", dir, e)
            null
        }
    }

    override suspend fun enqueue(
        request: DownloadRequest,
        totalBytes: Long?,
        files: List<DownloadFileMetadata>?,
        scheduledAt: Instant?,
        priority: DownloadPriority?
    ): DownloadId {
        val id = DownloadId.generate()
        val resolvedPriority = priority ?: settingsRepository.settings.value.defaultDownloadPriority
        val finalRequest = if (request is DownloadRequest.Torrent &&
            (request.torrentSource is TorrentSource.Magnet || request.torrentSource is TorrentSource.Url)
        ) {
            val key = getCacheKey(request)
            val cachedTorrentFile = repository.storageDir.resolve("cache").resolve("$key.torrent")
            if (Files.isRegularFile(cachedTorrentFile)) {
                request.copy(
                    torrentSource = TorrentSource.File(
                        cachedTorrentFile,
                        request.torrentSource
                    )
                )
            } else {
                request
            }
        } else {
            request
        }

        val task = DownloadTask(
            id = id,
            name = finalRequest.name,
            request = finalRequest,
            state = DownloadState.Queued,
            totalBytes = totalBytes,
            downloadedBytes = 0,
            downloadSpeed = 0,
            scheduledAt = scheduledAt,
            priority = resolvedPriority,
            files = files
        )
        tasks.update { it + (id to task) }
        scheduler.enqueue(task)
        persist()
        return id
    }

    override suspend fun start(id: DownloadId) {
        scheduler.start(id)
    }

    override suspend fun pause(id: DownloadId) {
        scheduler.pause(id)
        persist()
    }

    override suspend fun resume(id: DownloadId) {
        scheduler.start(id)
    }

    override suspend fun cancel(id: DownloadId) {
        scheduler.cancel(id)
        persist()
    }

    override suspend fun retry(id: DownloadId) {
        scheduler.start(id)
    }

    override suspend fun remove(id: DownloadId, deleteFiles: Boolean) {
        val task = tasks.value[id]
        scheduler.remove(id)
        tasks.update { it - id }
        cleanupMetadata(id, task?.request)

        if (deleteFiles && task != null) {
            withContext(Dispatchers.IO) {
                val dest = task.request.destination
                if (task.request is DownloadRequest.Torrent) {
                    if (storage.exists(dest)) {
                        runCatching { storage.deleteRecursively(dest) }
                            .onFailure {
                                logger.warn(
                                    "Failed to delete the torrent folder {} (download {})",
                                    dest,
                                    id,
                                    it
                                )
                            }
                    }
                } else {
                    listOf(
                        dest,
                        storage.getPartialFile(dest).toPath(),
                        storage.getResumeStateFile(dest).toPath(),
                    ).filter(storage::exists).forEach { path ->
                        runCatching { storage.delete(path) }
                            .onFailure {
                                logger.warn(
                                    "Failed to delete {} (download {})",
                                    path,
                                    id,
                                    it
                                )
                            }
                    }
                }
            }
        }
        persist()
    }

    override suspend fun setPriority(id: DownloadId, priority: DownloadPriority) {
        scheduler.setPriority(id, priority)
        persist()
    }

    override suspend fun getTask(id: DownloadId): DownloadTask? = tasks.value[id]

    override fun observeTask(id: DownloadId): Flow<DownloadTask> = scheduler.observeTask(id)

    override fun observeAllTasks(): StateFlow<List<DownloadTask>> = scheduler.observeAllTasks()

    override suspend fun shutdown() {
        scheduler.shutdown()
        writeSnapshot()
        persistSignal.close()
        httpClient.close()
        scope.cancel()
    }
}