package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.blaze.domain.repository.DownloadMetadata

class BatchDownloadItem(
    val url: String,
    initialMetadata: DownloadMetadata? = null
) {
    var metadata by mutableStateOf(initialMetadata)
    var isFetching by mutableStateOf(false)
    var isSelected by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)

    /**
     * Which of a multi-file torrent's files this entry should download. Seeded with everything so
     * plain links behave as before; the queue step sends exactly this set, so a deselection made
     * while reviewing a batch entry actually survives into the download.
     */
    var selectedFileIndices by mutableStateOf(
        initialMetadata?.files?.map { it.index }?.toSet() ?: emptySet()
    )
}