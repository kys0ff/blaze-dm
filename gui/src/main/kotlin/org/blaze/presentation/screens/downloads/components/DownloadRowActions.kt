package org.blaze.presentation.screens.downloads.components

/**
 * Every user-invokable operation for a single download row. Bundling them keeps the row's
 * signature focused and lets the hover buttons and the right-click menu share one wiring
 * point. Each callback is already bound to its [org.blaze.presentation.screens.downloads.DownloadsEvent]
 * by [DownloadsList]; the row only decides when to surface them.
 */
class DownloadRowActions(
    val pause: () -> Unit,
    val resume: () -> Unit,
    val retry: () -> Unit,
    val cancel: () -> Unit,
    val remove: () -> Unit,
    val openFile: () -> Unit,
    val showInFolder: () -> Unit,
    val openSourceLink: () -> Unit,
    val copyLink: () -> Unit,
    val copyFileLocation: () -> Unit,
    val showDetails: () -> Unit,
)
