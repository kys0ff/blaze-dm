package org.blaze.tray.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import org.blaze.tray.api.TrayConfig
import org.blaze.tray.api.TrayService

/**
 * Compose lifecycle wrapper around a [TrayService]: owns the install/sync/dispose
 * choreography so screens only declare *what* the tray should show, never *when*
 * the backend is touched.
 *
 * - (Re)installs the icon when [enabled] or [config] change (remember the
 *   [TrayConfig] upstream so it is only recreated on real changes, e.g. locale),
 *   and always removes it when the hosting composition leaves — the icon can
 *   never linger after exit.
 * - Keeps every window-toggle menu item in sync with [windowVisible].
 * - Degrades to a no-op when the service reports [TrayService.isSupported] false.
 *
 * Typical usage inside an `ApplicationScope`:
 * ```
 * TrayHost(
 *     service = trayService,
 *     enabled = settings.trayEnabled,
 *     config = trayConfig,
 *     windowVisible = windowVisible,
 * )
 * ```
 * The toggle callback in [config] only flips the caller's state; the wrapper
 * propagates the new visibility back into the menu.
 */
@Composable
fun TrayHost(
    service: TrayService,
    enabled: Boolean,
    config: TrayConfig,
    windowVisible: Boolean,
) {
    val active = enabled && service.isSupported

    DisposableEffect(active, config) {
        if (active) service.install(config)
        onDispose { if (active) service.dispose() }
    }

    LaunchedEffect(active, windowVisible) {
        if (active) service.setWindowVisible(windowVisible)
    }
}
