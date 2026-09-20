package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.domain.repository.DownloadMetadata

class AddDownloadState(
    defaultDestination: String
) {
    var url by mutableStateOf(TextFieldValue(""))
    var destination by mutableStateOf(TextFieldValue(defaultDestination))
    var scheduleDelay by mutableStateOf(TextFieldValue(""))
    var showFolderPicker by mutableStateOf(false)
    var showFilePicker by mutableStateOf(false)

    var metadata by mutableStateOf<DownloadMetadata?>(null)
    var selectedFileIndices by mutableStateOf(emptySet<Int>())
    var isFetching by mutableStateOf(false)
    var step by mutableStateOf(1) // 1: Input, 2: Metadata/Batch

    val batchItems = mutableStateListOf<BatchDownloadItem>()
    val isBatch: Boolean get() = batchItems.isNotEmpty()

    val source: String get() = url.text.trim()

    val canFetch: Boolean get() = source.isNotEmpty() && (isBatch || source.isSupportedSource())
    val looksSupported: Boolean get() = source.isEmpty() || isBatch || source.isSupportedSource()

    fun resetMetadata() {
        metadata = null
        selectedFileIndices = emptySet()
        batchItems.clear()
    }

    private fun String.isSupportedSource(): Boolean {
        val s = trim().lowercase()
        return s.startsWith("http://") ||
                s.startsWith("https://") ||
                s.startsWith("magnet:") ||
                s.endsWith(".torrent")
    }
}
