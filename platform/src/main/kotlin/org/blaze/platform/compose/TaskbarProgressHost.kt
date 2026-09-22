package org.blaze.platform.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import org.blaze.platform.taskbar.TaskbarProgressService

/**
 * Compose lifecycle wrapper around a [TaskbarProgressService]: owns the
 * update/dispose choreography so screens only declare *what* progress to show,
 * never when the backend is touched.
 *
 * - `progress == null` (or a value outside `0f..1f`, clamped by the service) hides
 *   the bar, so an idle app never carries a stale bar.
 * - [TaskbarProgressService.dispose] runs when the hosting composition leaves; the
 *   desktop-integration resources can never leak past app exit.
 * - Degrades to a no-op when the service reports [TaskbarProgressService.isSupported]
 *   false, so it is always safe to keep in the composition.
 *
 * Typical usage — the app aggregates its own work into a `Float?` state (upstream
 * callers should bucket/`distinctUntilChanged` frequent ticks so the desktop is not
 * flooded):
 * ```
 * TaskbarProgressHost(service, aggregateProgress)
 * ```
 */
@Composable
fun TaskbarProgressHost(
    service: TaskbarProgressService,
    progress: Float?,
) {
    DisposableEffect(service) {
        onDispose { service.dispose() }
    }

    LaunchedEffect(service, progress) {
        if (!service.isSupported) return@LaunchedEffect
        if (progress == null) service.clear() else service.setProgress(progress)
    }
}
