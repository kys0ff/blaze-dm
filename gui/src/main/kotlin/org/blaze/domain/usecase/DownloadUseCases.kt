package org.blaze.domain.usecase

import kotlinx.coroutines.flow.Flow
import org.blaze.domain.models.Download
import org.blaze.domain.repository.DownloadFile
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.domain.repository.DownloadRepository
import org.blaze.domain.repository.PortableInfo

class GetDownloadsUseCase(private val repository: DownloadRepository) {
    operator fun invoke(): Flow<List<Download>> = repository.downloads
}

class FetchMetadataUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(url: String): DownloadMetadata? = repository.fetchMetadata(url)
}

class AddDownloadUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(
        url: String,
        savePath: String,
        name: String? = null,
        fileIndices: List<Int>? = null,
        totalSize: Long? = null,
        files: List<DownloadFile>? = null,
        scheduledAt: Long? = null
    ) = repository.addDownload(url, savePath, name, fileIndices, totalSize, files, scheduledAt)
}

class GetDestinationPathUseCase(private val repository: DownloadRepository) {
    suspend operator fun invoke(url: String, savePath: String, name: String? = null): String =
        repository.getDestinationPath(url, savePath, name)
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
