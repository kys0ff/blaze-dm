package org.blaze.engine.core

import bt.bencoding.serializers.BEParser
import bt.metainfo.MetadataConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.blaze.engine.api.DownloadEngine
import org.blaze.engine.api.DownloadError
import org.blaze.engine.api.DownloadFileMetadata
import org.blaze.engine.api.DownloadId
import org.blaze.engine.api.DownloadMetadata
import org.blaze.engine.api.DownloadPriority
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.api.DownloadState
import org.blaze.engine.api.DownloadTask
import org.blaze.engine.api.PortableDownloadInfo
import org.blaze.engine.api.PortableTransferKind
import org.blaze.engine.api.TorrentSource
import org.blaze.engine.api.TorrentFilePreview
import org.blaze.engine.execution.DownloadExecutor
import org.blaze.engine.execution.DownloadExecutorImpl
import org.blaze.engine.metrics.EngineMetrics
import org.blaze.engine.network.BandwidthLimiter
import org.blaze.engine.network.HttpNetworkClient
import org.blaze.engine.persistence.DownloadRecord
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.portable.PortableImport
import org.blaze.engine.portable.PortableManifest
import org.blaze.engine.portable.TorrentPortableFile
import org.blaze.engine.portable.inspectPortableFile
import org.blaze.engine.portable.installHttpResumeSidecar
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
                cleanupMetadata(task.id)
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
            // Progress ticks arrive many times per second per download; collapsing them keeps a
            // large queue from turning every tick into a full snapshot rewrite.
            while (true) {
                if (persistSignal.receiveCatching().isClosed) break
                delay(PERSIST_DEBOUNCE)
                while (persistSignal.tryReceive().isSuccess) { /* conflated: nothing to drain */ }
                try {
                    writeSnapshot()
                } catch (e: Exception) {
                    logger.error("Failed to persist the download list", e)
                }
            }
        }
        // The throttle is engine-wide, so its configuration belongs to the engine and not to the
        // first download that starts: editing the limit in the settings now applies to transfers
        // that are already running. Deriving the bytes/sec first means unrelated settings edits
        // cannot reset a token bucket mid-download.
        // Snail Mode wins over the normal global limit: when it is on, everything crawls.
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
         * It deliberately uses the JDK's own [java.net.http.HttpClient] engine rather than Ktor's
         * CIO engine: CIO opens a brand-new TCP connection for *every* request (verified - two
         * sequential keep-alive GETs use two sockets), so a segmented download would pay a full
         * TCP - and on a real host, TLS - handshake for each of its dozens of chunk requests, and
         * the abandoned sockets pile up in TIME_WAIT. The JDK client pools and reuses keep-alive
         * connections and speaks HTTP/2, so the workers reuse sockets instead.
         *
         * The JDK client has no per-socket read-inactivity timeout, so a stalled transfer is caught
         * by the segmented path's watchdog (see HttpDownloadCoordinator) rather than by
         * socketTimeoutMillis; the connect timeout still bounds dead hosts.
         */
        fun createDefaultHttpClient(): HttpClient = HttpClient(Java) {
            install(HttpTimeout) {
                // No absolute cap on the whole call: a streaming download of a large or
                // slow file can legitimately take longer than any fixed timeout, and a
                // finite request timeout would kill it mid-body with "Request timeout has
                // expired" on every attempt.
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
                // The magnet/URL a record was originally queued with rides in `magnetUri`/`torrentUrl`
                // even once its metainfo is cached to a `.torrent`, so the restored source keeps the
                // user-facing locator rather than the cache path.
                val origin = when {
                    record.magnetUri != null -> TorrentSource.Magnet(record.magnetUri)
                    record.torrentUrl != null -> TorrentSource.Url(record.torrentUrl)
                    else -> null
                }
                val cachedFile = record.torrentPath?.let { Path.of(it) }
                val source: TorrentSource = when {
                    // A cache file still on disk lets the transfer skip re-resolving the swarm; the
                    // origin stays attached so display/copy report what the user actually queued.
                    cachedFile != null && Files.isRegularFile(cachedFile) -> TorrentSource.File(cachedFile, origin)
                    // Cache gone (or never built): fall back to the original live source.
                    origin != null -> origin
                    cachedFile != null -> TorrentSource.File(cachedFile)
                    else -> error("Torrent record ${record.id} missing every torrent source field")
                }
                DownloadRequest.Torrent(record.name, source, Path.of(record.destination), record.fileIndices)
            }
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
            // What the last attempt learned about the source survives a restart, so a download the
            // user paused before closing the app is still warned about correctly when they resume it.
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
            // A cached `.torrent` also records the magnet/URL it was resolved from (its origin), so a
            // restart rebuilds the same user-facing source rather than surfacing the cache path.
            val effectiveOrigin = (torrentSource as? TorrentSource.File)?.origin ?: torrentSource
            DownloadRecord(
                id = task.id.value,
                name = task.name,
                type = when (task.request) {
                    is DownloadRequest.Http -> "HTTP"
                    is DownloadRequest.Torrent -> "TORRENT"
                },
                url = (task.request as? DownloadRequest.Http)?.url,
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
                resumeSupported = task.resumeSupported
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
                val torrentFile = cacheDir.resolve("${id.value}.torrent")
                Files.write(torrentFile, bytes)

                // Swap the live source for its cached metainfo so a restart skips re-resolving the
                // swarm, but remember the magnet/URL it came from: the displayed/copied source stays
                // what the user queued, not the internal cache path.
                val origin = request.torrentSource.takeIf {
                    it is TorrentSource.Magnet || it is TorrentSource.Url
                }
                val newRequest = request.copy(torrentSource = TorrentSource.File(torrentFile, origin))
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
            is DownloadRequest.Torrent -> when (val s = request.torrentSource) {
                is TorrentSource.Magnet -> s.uri
                is TorrentSource.File -> s.path.toAbsolutePath().toString()
                is TorrentSource.Url -> s.url
            }
        }
        return sha256(sourceString)
    }

    /** A stable, filesystem-safe cache identity for a source string: its lowercase SHA-256 hex. */
    private fun sha256(sourceString: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(sourceString.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    private fun cleanupMetadata(id: DownloadId) {
        scope.launch(Dispatchers.IO) {
            try {
                val torrentFile = repository.storageDir.resolve("cache").resolve("${id.value}.torrent")
                storage.delete(torrentFile)
            } catch (e: Exception) {
                logger.error("Failed to cleanup metadata", e)
            }
        }
    }

    override suspend fun fetchMetadata(request: DownloadRequest): DownloadMetadata? = withContext(Dispatchers.IO) {
        if (request is DownloadRequest.Http) {
            // A HEAD/range probe answers this; running the real transfer would write a stray
            // partial file and could not be stopped cleanly halfway through.
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
                    // The probe already had to prove ranged reads (or watch a `Range` request get
                    // answered with a whole body), so this is a real answer about resumability.
                    resumeSupported = probe.rangeSupport
                )
            }
        }

        val tempId = DownloadId.generate()
        val tempTask = DownloadTask(tempId, request.name, request, DownloadState.Starting, null, 0, 0)
        
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
                } catch (_: Exception) {}
            }
        )

        val metadata = withTimeoutOrNull(30.seconds) {
            executor.execute().firstOrNull { it.totalBytes != null && it.totalBytes > 0 }
        }

        metadata?.let { DownloadMetadata(it.name, it.totalBytes, it.files, resumeSupported = true) }
    }

    /**
     * Fetches the `.torrent` served at [url] over HTTP(S), validates it is metainfo, caches it under the
     * source's hash and parses it into a preview. The caller starts the download from the returned cached
     * file, so it behaves exactly like a torrent the user picked from disk rather than a URL the swarm has
     * to resolve again.
     */
    override suspend fun fetchTorrentFile(url: String, credentials: Map<String, String>): TorrentFilePreview? =
        withContext(Dispatchers.IO) {
            val trimmed = url.trim()
            if (!(trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true))) {
                return@withContext null
            }

            val client = HttpNetworkClient(
                client = httpClient,
                userAgent = settingsRepository.settings.value.httpUserAgent.ifBlank { DEFAULT_USER_AGENT },
                maxRedirects = settingsRepository.settings.value.maxRedirects
            )
            val httpRequest = DownloadRequest.Http(
                name = trimmed.substringAfterLast('/').substringBefore('?').ifBlank { "download.torrent" },
                url = trimmed,
                destination = repository.storageDir.resolve("cache"),
                headers = credentials,
            )

            // Buffer the whole (bounded) body: a `.torrent` is a tiny descriptor, so reading it into
            // memory is cheaper than streaming it to disk and lets a non-torrent body be rejected before
            // anything is cached. Exceeding the cap throws inside the sink, which surfaces as a Failed.
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

                // Reading a File source resolves the name/size/file list without waiting on the swarm,
                // so the preview reflects the exact metainfo the download will start from.
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

    private suspend fun importHttp(artifact: Path, destination: Path, info: PortableDownloadInfo): DownloadId? {
        val finalFile = destination.resolve(info.suggestedName)
        val partial = storage.getPartialFile(finalFile).toPath()
        // Bring the moved file to the canonical partial path the engine expects. Prefer a move; fall
        // back to a copy when it crosses a device boundary (§22 permits copy as well as move).
        if (runCatching { artifact.toRealPath() }.getOrNull() != runCatching { partial.toRealPath() }.getOrNull()) {
            runCatching { Files.createDirectories(partial.parent) }
            val moved = runCatching { Files.move(artifact, partial, StandardCopyOption.REPLACE_EXISTING) }.isSuccess
            if (!moved) runCatching { Files.copy(artifact, partial, StandardCopyOption.REPLACE_EXISTING) }
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
            // The metainfo blob is written on the IO dispatcher, off whichever thread the caller's
            // import coroutine happens to be resumed on, so the disk flush cannot starve a shared pool.
            withContext(Dispatchers.IO) { Files.write(torrentFile, record.manifest.torrentFile) }
            val files = record.manifest.files.map { DownloadFileMetadata(it.relativePath, it.size) }
            enqueue(
                DownloadRequest.Torrent(
                    name = record.manifest.name,
                    torrentSource = TorrentSource.File(torrentFile),
                    destination = dir,
                    // Honor the original partial selection: without this, files the user skipped
                    // on machine A would all be downloaded again after moving the folder.
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
        // A null priority means "no explicit choice": fall back to the engine-wide default the user
        // configured, so an omitted value is not silently pinned to NORMAL.
        val resolvedPriority = priority ?: settingsRepository.settings.value.defaultDownloadPriority
        val finalRequest = if (request is DownloadRequest.Torrent &&
            (request.torrentSource is TorrentSource.Magnet || request.torrentSource is TorrentSource.Url)
        ) {
            // Metainfo resolved by an earlier run rides along in the cache: skip re-fetching it
            // (magnet via DHT, torrent URL via HTTP) and start straight from the stored file.
            val key = getCacheKey(request)
            val cachedTorrentFile = repository.storageDir.resolve("cache").resolve("$key.torrent")
            if (Files.isRegularFile(cachedTorrentFile)) {
                // Keep the magnet/URL as the file's origin so the user still sees (and copies) the
                // source they actually queued rather than the cached metainfo path.
                request.copy(torrentSource = TorrentSource.File(cachedTorrentFile, request.torrentSource))
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
        cleanupMetadata(id)

        if (deleteFiles && task != null) {
            // Awaited rather than fired and forgotten: whoever removes a download's files may turn
            // around and start another download in the same folder, and "is that name free?" is only
            // answerable once the deletion it just asked for has actually happened.
            withContext(Dispatchers.IO) {
                val dest = task.request.destination
                if (task.request is DownloadRequest.Torrent) {
                    // A torrent's destination *is* its folder - the content sits directly inside
                    // it (see org.blaze.engine.storage.TorrentContentStorage), along with the
                    // portable marker - so removing the download means removing that folder, and
                    // nothing above it. A folder that is not there means there is nothing to delete:
                    // a clean removal, not a failure, so it stays quiet.
                    if (storage.exists(dest)) {
                        runCatching { storage.deleteRecursively(dest) }
                            .onFailure { logger.warn("Failed to delete the torrent folder {} (download {})", dest, id, it) }
                    }
                } else {
                    // An HTTP download leaves up to three artifacts behind - the finished file, the
                    // partial and the resume-metadata sidecar. Delete only those actually present, so
                    // a download that never wrote bytes is a silent no-op rather than a failed delete,
                    // and a single artifact that resists removal is named precisely instead of reading
                    // as a wholesale failure.
                    listOf(
                        dest,
                        storage.getPartialFile(dest).toPath(),
                        storage.getResumeStateFile(dest).toPath(),
                    ).filter(storage::exists).forEach { path ->
                        runCatching { storage.delete(path) }
                            .onFailure { logger.warn("Failed to delete {} (download {})", path, id, it) }
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