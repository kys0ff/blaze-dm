package org.blaze.domain.usecase

import kotlinx.coroutines.flow.Flow
import org.blaze.domain.models.Download
import org.blaze.domain.repository.*
import org.blaze.download.api.DownloadPriority

class GetDownloadsUseCase(private val repository: DownloadRepository) {
    operator fun invoke(): Flow<List<Download>> = repository.downloads
}

class FetchMetadataUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(
        url: String,
        credentials: Map<String, String> = emptyMap(),
    ): DownloadMetadata? = repository.fetchMetadata(url, credentials)
}

class PrepareTorrentUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(
        url: String,
        credentials: Map<String, String> = emptyMap(),
    ): TorrentPreview? = repository.prepareTorrentFromUrl(url, credentials)
}

class AddDownloadUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(
        url: String,
        savePath: String,
        name: String? = null,
        fileIndices: List<Int>? = null,
        totalSize: Long? = null,
        files: List<DownloadFile>? = null,
        scheduledAt: Long? = null,
        priority: DownloadPriority? = null,
        credentials: Map<String, String> = emptyMap(),
    ) = repository.addDownload(url, savePath, name, fileIndices, totalSize, files, scheduledAt, priority, credentials)
}

class GetDestinationPathUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(url: String, savePath: String, name: String? = null): String =
        repository.getDestinationPath(url, savePath, name)
}

/**
 * Inspects an HLS playlist before adding it — lists variants and classifies protection without
 * downloading media. The dialog turns the verdict into a potential-DRM warning or a quality picker.
 */
class InspectHlsUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(
        url: String,
        variantIndex: Int = -1,
        credentials: Map<String, String> = emptyMap(),
    ): org.blaze.download.api.HlsInspectionDto = repository.inspectHls(url, variantIndex, credentials)
}

/**
 * Inspects a remote ZIP via byte ranges, returning its entry tree and whether selective retrieval is
 * possible, without downloading the whole archive.
 */
class InspectArchiveUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(
        url: String,
        credentials: Map<String, String> = emptyMap(),
    ): org.blaze.download.api.ArchiveInspectionDto = repository.inspectArchive(url, credentials)
}

/**
 * Queues a download described by an explicit typed [source] (an HLS playlist with a chosen variant and
 * DRM choice, or a remote archive with a chosen entry selection) rather than a bare URL.
 */
class AddDownloadWithSourceUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(
        source: org.blaze.download.api.DownloadSource,
        savePath: String,
        name: String? = null,
        totalSize: Long? = null,
        scheduledAt: Long? = null,
        priority: DownloadPriority? = null,
        credentials: Map<String, String> = emptyMap(),
    ) = repository.addDownloadWithSource(source, savePath, name, totalSize, scheduledAt, priority, credentials)
}

class PauseDownloadUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(id: String) = repository.pauseDownload(id)
}

class ResumeDownloadUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(id: String) = repository.resumeDownload(id)
}

class CancelDownloadUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(id: String) = repository.cancelDownload(id)
}

class RemoveDownloadUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(id: String, deleteFile: Boolean = false) =
        repository.removeDownload(id, deleteFile)
}

class RetryDownloadUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(id: String) = repository.retryDownload(id)
}

class SetPriorityDownloadUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(id: String, priority: DownloadPriority) =
        repository.setPriority(id, priority)
}

class PauseAllDownloadsUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke() = repository.pauseAll()
}

class ResumeAllDownloadsUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke() = repository.resumeAll()
}

class ClearCompletedDownloadsUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke() = repository.clearCompleted()
}

class DetectPortableDownloadUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(artifactPath: String): PortableInfo? =
        repository.detectPortable(artifactPath)
}

class ImportPortableDownloadUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(artifactPath: String, destinationDir: String): Boolean =
        repository.importPortable(artifactPath, destinationDir)
}
