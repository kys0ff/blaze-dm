package org.blaze.platform.autostart

import org.slf4j.LoggerFactory

/**
 * Generic bridge between a *desired* run-at-startup state and the OS registration,
 * so callers never touch an [AutoStartService] directly:
 *
 * - [applyLive] pushes a desired state to the OS right now;
 * - [syncTo] reconciles the OS with a desired state, touching it only on drift.
 *
 * Where the desire comes from (a persisted settings file, a Compose state, ...) is
 * deliberately left to the app; see `AutoStartHost` for the Compose wrapper and the
 * app-side coordinator that pairs this with settings persistence.
 */
class AutoStartController(
    private val service: AutoStartService,
) {
    private val logger = LoggerFactory.getLogger(AutoStartController::class.java)

    val isSupported: Boolean get() = service.isSupported

    /** Write/remove the OS registration to match [enabled]; failures are logged, never thrown. */
    fun applyLive(enabled: Boolean) {
        if (!service.isSupported) return
        val result = if (enabled) service.enable() else service.disable()
        result.onFailure { logger.warn("Failed to update the run-at-startup registration", it) }
    }

    /** Touch the OS only when its actual state differs from the desired [enabled] state. */
    fun syncTo(enabled: Boolean) {
        if (!service.isSupported) return
        val actual = runCatching { service.isEnabled() }
            .onFailure { logger.warn("Failed to inspect the run-at-startup registration", it) }
            .getOrNull() ?: return
        if (actual != enabled) applyLive(enabled)
    }
}
