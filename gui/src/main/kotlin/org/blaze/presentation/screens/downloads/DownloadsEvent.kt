package org.blaze.presentation.screens.downloads

import org.blaze.domain.repository.DownloadFile

sealed interface DownloadsEvent {
    data class SearchChanged(val query: String) : DownloadsEvent
    data class AddDownload(
        val url: String,
        val savePath: String,
        val name: String? = null,
        val fileIndices: List<Int>? = null,
        val totalSize: Long? = null,
        val files: List<DownloadFile>? = null
    ) : DownloadsEvent
    data class Pause(val id: String) : DownloadsEvent
    data class Resume(val id: String) : DownloadsEvent
    data class Remove(val id: String, val deleteFile: Boolean) : DownloadsEvent
    data class Retry(val id: String) : DownloadsEvent
    data class Cancel(val id: String) : DownloadsEvent
    data object PauseAll : DownloadsEvent
    data object ResumeAll : DownloadsEvent
    data object ClearCompleted : DownloadsEvent
}
