package org.blaze.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import org.blaze.domain.models.Download
import org.blaze.domain.models.SelectedFile
import org.blaze.domain.repository.DownloadFile
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.domain.repository.DownloadRepository
import org.blaze.domain.repository.PortableInfo
import org.blaze.domain.repository.PortableKind
import org.blaze.download.api.DownloadDto
import org.blaze.download.api.DownloadFileHint
import org.blaze.download.api.DownloadPriority
import org.blaze.download.api.DownloadSource
import org.blaze.download.api.DownloadState as ApiState
import org.blaze.download.api.NewDownloadRequest
import org.blaze.download.api.PortableInfoDto
import org.blaze.download.api.DownloadsApi
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.blaze.domain.models.DownloadState as GuiState

/**
 * The GUI's view onto the download subsystem - implemented entirely against the application-level
 * [DownloadsApi], never the engine.
 *
 * Everything the window needs (the live list, lifecycle verbs, metadata probing, portable import) goes
 * through the same boundary the CLI and a future browser bridge use, so the GUI is just another client:
 * it maps the transport-agnostic [DownloadDto] onto its own [Download] view model and builds a
 * [NewDownloadRequest] to add work. No coroutine scope, scheduler, worker or engine request type appears
 * here, which is what lets the transfer implementation change without touching a single line of UI code.
 */
class DownloadRepositoryImpl(
    private val api: DownloadsApi,
    private val scope: CoroutineScope,
) : DownloadRepository {

    override val downloads: Flow<List<Download>> =
        api.observe()
            .map { dtos -> dtos.map { it.toGuiDownload() } }
            .shareIn(
                scope = scope,
                started = SharingStarted.Lazily,
                replay = 1,
            )

    override suspend fun fetchMetadata(url: String, credentials: Map<String, String>): DownloadMetadata? {
        val request = NewDownloadRequest(
            source = sourceOf(url),
            destination = TMP_DIR,
            filename = null,
            credentials = credentials,
        )
        return api.probe(request)?.let { meta ->
            DownloadMetadata(
                name = meta.name,
                totalSize = meta.totalBytes,
                files = meta.files?.mapIndexed { index, file ->
                    DownloadFile(name = file.path, size = file.size, index = index)
                },
                resumeSupported = meta.resumeSupported,
            )
        }
    }

    override suspend fun addDownload(
        url: String,
        savePath: String,
        name: String?,
        fileIndices: List<Int>?,
        totalSize: Long?,
        files: List<DownloadFile>?,
        scheduledAt: Long?,
        priority: DownloadPriority?,
        credentials: Map<String, String>,
    ) {
        val destinationDir = Path.of(savePath)
        withContext(Dispatchers.IO) {
            if (Files.notExists(destinationDir)) Files.createDirectories(destinationDir)
        }

        val request = NewDownloadRequest(
            source = sourceOf(url),
            destination = savePath,
            filename = name?.takeIf { it.isNotBlank() },
            autoStart = true,
            expectedSizeBytes = totalSize,
            selectedFileIndices = fileIndices,
            files = files?.map { DownloadFileHint(path = it.name, size = it.size) },
            scheduledAtEpochMs = scheduledAt,
            priority = priority,
            credentials = credentials,
        )
        api.create(request)
    }

    override suspend fun getDestinationPath(url: String, savePath: String, name: String?): String =
        resolvePreview(url, savePath, name).toAbsolutePath().toString()

    override suspend fun pauseDownload(id: String) = api.pause(id)
    override suspend fun resumeDownload(id: String) = api.resume(id)
    override suspend fun cancelDownload(id: String) = api.cancel(id)
    override suspend fun retryDownload(id: String) = api.retry(id)
    override suspend fun setPriority(id: String, priority: DownloadPriority) = api.setPriority(id, priority)
    override suspend fun removeDownload(id: String, deleteFile: Boolean) = api.remove(id, deleteFile)

    override suspend fun pauseAll() {
        api.snapshot().forEach { if (it.state.isGuiActive()) api.pause(it.id) }
    }

    override suspend fun resumeAll() {
        api.snapshot().forEach { if (it.state == ApiState.PAUSED || it.state == ApiState.PAUSING) api.resume(it.id) }
    }

    override suspend fun clearCompleted() {
        api.snapshot().forEach {
            if (it.state == ApiState.COMPLETED || it.state == ApiState.SEEDING) api.remove(it.id, deleteFiles = false)
        }
    }

    override suspend fun detectPortable(artifactPath: String): PortableInfo? =
        api.detectPortable(artifactPath)?.toPortableInfo()

    override suspend fun importPortable(artifactPath: String, destinationDir: String): Boolean {
        val destination = Path.of(destinationDir)
        withContext(Dispatchers.IO) {
            if (Files.notExists(destination)) Files.createDirectories(destination)
        }
        return api.importPortable(artifactPath, destinationDir) != null
    }

    private fun PortableInfoDto.toPortableInfo() = PortableInfo(
        kind = when (this.kind) {
            org.blaze.download.api.PortableKindDto.HTTP -> PortableKind.HTTP
            org.blaze.download.api.PortableKindDto.TORRENT -> PortableKind.TORRENT
        },
        suggestedName = suggestedName,
        source = source,
        totalBytes = totalBytes,
        availableBytes = availableBytes,
        resumable = resumable,
        multiFile = multiFile,
        mayRequireCredentials = mayRequireCredentials,
    )

    private fun DownloadDto.toGuiDownload(): Download {
        val selected = files?.filter { it.selected }
        val total = totalBytes
        val frac = progress
        return Download(
            id = id,
            name = name,
            url = source.locator,
            totalSize = total,
            downloadedSize = if (state == ApiState.COMPLETED || state == ApiState.SEEDING) {
                total ?: downloadedBytes
            } else if (downloadedBytes == 0L && total != null && (frac ?: 0f) > 0f) {
                (frac!! * total).toLong()
            } else {
                downloadedBytes
            },
            speed = speedBytesPerSecond,
            eta = etaSeconds,
            peers = peers,
            state = state.toGuiState(),
            addedAt = createdAtEpochMs,
            scheduledAt = scheduledAtEpochMs,
            savePath = destination,
            error = error,
            selectedFiles = selected?.mapIndexed { index, f -> SelectedFile(path = f.path, size = f.size, index = index) },
            resumeSupported = sourceSupportsResume,
            priority = priority,
        )
    }

    private fun ApiState.toGuiState(): GuiState = when (this) {
        ApiState.QUEUED -> GuiState.QUEUED
        ApiState.STARTING, ApiState.DOWNLOADING, ApiState.RESUMING,
        ApiState.VERIFYING, ApiState.RESOLVING_METADATA, ApiState.COMPLETING -> GuiState.DOWNLOADING
        ApiState.PAUSED, ApiState.PAUSING -> GuiState.PAUSED
        ApiState.COMPLETED -> GuiState.COMPLETED
        ApiState.SEEDING -> GuiState.SEEDING
        ApiState.FAILED, ApiState.CANCELLED -> GuiState.FAILED
    }

    private fun ApiState.isGuiActive(): Boolean =
        this == ApiState.DOWNLOADING || this == ApiState.QUEUED || this == ApiState.STARTING ||
            this == ApiState.RESUMING || this == ApiState.VERIFYING || this == ApiState.RESOLVING_METADATA

    companion object {
        private const val TMP_DIR = "/tmp"
    }
}

/**
 * Picks the right [DownloadSource] from a raw GUI url/magnet entry.
 *
 * A `.torrent` ending only names a *torrent source* when the value is a local path or a web URL
 * serving metainfo; the service's mapper tells those two apart by scheme, so an http(s) URL that
 * happens to end in `.torrent` joins the torrent family here instead of being saved as an
 * ordinary file (which is how a pasted "…/linux.torrent" link used to land).
 */
internal fun sourceOf(rawUrl: String): DownloadSource {
    val url = rawUrl.trim()
    // The query never names the file: "…/My.torrent?token=1" is still a torrent source.
    val pathPart = url.substringBefore('?')
    return when {
        url.startsWith("magnet:", ignoreCase = true) -> DownloadSource.Magnet(url)
        pathPart.endsWith(".torrent", ignoreCase = true) -> DownloadSource.TorrentFile(url)
        else -> DownloadSource.Url(url)
    }
}

/**
 * A display-only preview of where [url] will land, matching the naming the application service applies
 * when it builds the engine request. It never creates anything; it exists so the "Save as" dialog can
 * echo the resulting path before the user commits.
 */
internal fun resolvePreview(url: String, savePath: String, name: String?): Path {
    val dir = Path.of(savePath)
    val explicit = name?.takeIf { it.isNotBlank() }
    return when (val source = sourceOf(url)) {
        is DownloadSource.TorrentFile -> {
            // A web URL's torrent folder is named by its decoded last segment, not by a filesystem
            // path parsed out of the whole URL; the fallback matches RequestMapper's, so the
            // preview never promises a name the service won't deliver.
            val filePart = source.path.substringBefore('?').substringAfterLast('/')
            val decoded = runCatching { URLDecoder.decode(filePart, StandardCharsets.UTF_8).ifBlank { filePart } }
                .getOrDefault(filePart)
            dir.resolve(explicit ?: removeTorrentSuffix(decoded).ifBlank { "torrent-download" })
        }
        is DownloadSource.Magnet ->
            dir.resolve(explicit ?: deriveMagnetName(source.uri))
        is DownloadSource.Url ->
            dir.resolve(explicit ?: deriveHttpName(source.url))
    }
}

/** `.torrent` is a source marker, not part of the display name; the ending is stripped case-insensitively. */
private fun removeTorrentSuffix(name: String): String =
    if (name.endsWith(".torrent", ignoreCase = true)) name.dropLast(".torrent".length) else name

private fun deriveHttpName(url: String): String {
    val decoded = runCatching { URLDecoder.decode(url, StandardCharsets.UTF_8).ifBlank { url } }.getOrDefault(url)
    return decoded
        .substringAfterLast('/')
        .substringBefore('?')
        .replace('+', ' ')
        .takeIf { it.isNotBlank() }
        ?: "download"
}

private fun deriveMagnetName(uri: String): String {
    val dn = uri.split('&', '?')
        .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrDefault(it) }
    return dn?.takeIf { it.isNotBlank() } ?: "torrent-download"
}
