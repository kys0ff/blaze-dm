package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.domain.repository.DownloadMetadata
import org.blaze.resolver.core.LoadedResolver

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

    // Link-handler resolution: the direct URL extracted from a page link, and the picker state.
    var resolvedDirectUrl by mutableStateOf<String?>(null)
    var showHandlerChoice by mutableStateOf(false)
    var handlerChoices by mutableStateOf<List<LoadedResolver>>(emptyList())
    var fetchError by mutableStateOf<String?>(null)

    /**
     * Enabled handlers that currently claim they can process [source]. Recomputed on every
     * URL edit by the parent dialog; drives the inline "this extension will be used" notice
     * so the user learns the outcome before pressing Fetch.
     */
    var detectedHandlers by mutableStateOf<List<LoadedResolver>>(emptyList())

    val batchItems = mutableStateListOf<BatchDownloadItem>()
    val isBatch: Boolean get() = batchItems.isNotEmpty()

    val source: String get() = url.text.trim()

    /** The URL actually handed to the engine: a resolved direct link when present. */
    val effectiveSource: String get() = resolvedDirectUrl ?: source

    val canFetch: Boolean get() = source.isNotEmpty() && (isBatch || source.isSupportedSource())
    val looksSupported: Boolean get() = source.isEmpty() || isBatch || source.isSupportedSource()

    fun resetMetadata() {
        metadata = null
        selectedFileIndices = emptySet()
        batchItems.clear()
        resolvedDirectUrl = null
        handlerChoices = emptyList()
        fetchError = null
        detectedHandlers = emptyList()
    }

    private fun String.isSupportedSource(): Boolean {
        val s = trim().lowercase()
        return s.startsWith("http://") ||
                s.startsWith("https://") ||
                s.startsWith("magnet:") ||
                s.endsWith(".torrent")
    }
}
