package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.domain.repository.DownloadRepository
import org.blaze.platform.taskbar.TaskbarProgressService
import org.koin.compose.koinInject

/**
 * Mirrors aggregate download progress onto the taskbar / dock icon. A no-op when the
 * platform backend reports [TaskbarProgressService.isSupported] false, so it is always
 * safe to keep in the composition.
 *
 * The bar tracks the in-flight queue: it shows the mean progress of every downloading
 * or queued item and clears once nothing is active, so an idle app never carries a
 * stale bar. `distinctUntilChanged` on a two-decimal bucket keeps the frequent
 * progress ticks from flooding the desktop with updates.
 */
@Composable
fun AppTaskbarProgress() {
    val service = koinInject<TaskbarProgressService>()
    val downloadRepository = koinInject<DownloadRepository>()

    DisposableEffect(service) {
        onDispose { service.dispose() }
    }

    LaunchedEffect(service, downloadRepository) {
        if (!service.isSupported) return@LaunchedEffect
        downloadRepository.downloads
            .map { downloads -> aggregateProgress(downloads)?.let { (it * 100).toInt() / 100f } }
            .distinctUntilChanged()
            .collect { progress ->
                if (progress == null) service.clear() else service.setProgress(progress)
            }
    }
}

/**
 * Mean progress across the active (downloading / queued) downloads, or `null` when
 * nothing is in flight - the state the taskbar bar should not be shown for.
 */
internal fun aggregateProgress(downloads: List<Download>): Float? {
    val active = downloads.filter {
        it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
    }
    if (active.isEmpty()) return null
    return active.map { it.progress.coerceIn(0f, 1f) }.average().toFloat()
}
