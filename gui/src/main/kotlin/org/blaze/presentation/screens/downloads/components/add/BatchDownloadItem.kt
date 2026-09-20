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
}