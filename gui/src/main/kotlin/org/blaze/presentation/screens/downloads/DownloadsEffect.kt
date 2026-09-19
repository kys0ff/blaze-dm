package org.blaze.presentation.screens.downloads

sealed interface DownloadsEffect {
    data class ShowError(val message: String) : DownloadsEffect
    data class ShowMessage(val message: String) : DownloadsEffect
}
