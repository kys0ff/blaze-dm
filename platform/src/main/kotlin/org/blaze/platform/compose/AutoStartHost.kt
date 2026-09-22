package org.blaze.platform.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.blaze.platform.autostart.AutoStartController

/**
 * Compose wrapper around an [AutoStartController]: keeps the OS run-at-startup
 * registration aligned with the [enabled] state observed by the composition.
 *
 * Every change goes through [AutoStartController.syncTo], which only touches the OS
 * when its actual state differs from the desired one - so the first composition
 * repairs drift (like a startup sync) and later edits apply immediately, without
 * redundant registry/plist writes.
 *
 * Apps that need *deferred* persistence (Apply/OK vs Cancel semantics in a settings
 * dialog) should drive the controller from their screen events instead of hosting
 * this composable on a draft state.
 */
@Composable
fun AutoStartHost(
    controller: AutoStartController,
    enabled: Boolean,
) {
    LaunchedEffect(controller, enabled) {
        controller.syncTo(enabled)
    }
}
