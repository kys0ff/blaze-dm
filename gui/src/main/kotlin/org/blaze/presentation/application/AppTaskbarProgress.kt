package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.domain.repository.DownloadRepository
import org.blaze.platform.compose.TaskbarProgressHost
import org.blaze.platform.taskbar.TaskbarProgressService
import org.koin.compose.koinInject

/**
 * Mirrors aggregate download progress onto the taskbar / dock icon through the
 * reusable [TaskbarProgressHost] wrapper, which owns the set/clear/dispose
 * choreography and no-ops on unsupported platforms.
 *
 * The Blaze-specific part is the state feeding the host: the bar tracks the
 * in-flight queue - mean progress of every downloading or queued item, cleared once
 * nothing is active. `distinctUntilChanged` on a two-decimal bucket keeps the
 * frequent progress ticks from flooding the desktop with updates.
 */
@Composable
fun AppTaskbarProgress() {
    val service = koinInject<TaskbarProgressService>()
    val downloadRepository = koinInject<DownloadRepository>()

    val progress by produceState<Float?>(initialValue = null, service, downloadRepository) {
        downloadRepository.downloads
            .map { downloads -> aggregateProgress(downloads)?.let { (it * 100).toInt() / 100f } }
            .distinctUntilChanged()
            .collect { value = it }
    }

    TaskbarProgressHost(service, progress)
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
