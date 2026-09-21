package org.blaze.platform.autostart

import org.blaze.data.AppSettingsRepository
import org.slf4j.LoggerFactory

/**
 * The single bridge between the persisted `runAtStartup` setting and the OS
 * registration, so neither the settings screen nor the repositories talk to an
 * [AutoStartService] directly:
 *
 * - [applyLive] pushes a desired state to the OS right now (settings Apply/OK);
 * - [revertToPersisted] rolls the OS back to what is stored on disk, undoing an
 *   unpersisted Apply when the settings screen is disposed;
 * - [sync] reconciles the OS with the persisted setting at app startup, repairing
 *   drift from a removed autostart entry or a settings file edited by hand.
 */
class AutoStartCoordinator(
    private val appSettingsRepository: AppSettingsRepository,
    private val service: AutoStartService
) {
    private val logger = LoggerFactory.getLogger(AutoStartCoordinator::class.java)

    val isSupported: Boolean get() = service.isSupported

    /** Write/remove the OS registration to match [enabled]; failures are logged, never thrown. */
    fun applyLive(enabled: Boolean) {
        if (!service.isSupported) return
        val result = if (enabled) service.enable() else service.disable()
        result.onFailure { logger.warn("Failed to update the run-at-startup registration", it) }
    }

    fun revertToPersisted() = syncTo(appSettingsRepository.persisted.runAtStartup)

    fun sync() = syncTo(appSettingsRepository.settings.value.runAtStartup)

    /** Touch the OS only when its actual state differs from the persisted desire. */
    private fun syncTo(desired: Boolean) {
        if (!service.isSupported) return
        val actual = runCatching { service.isEnabled() }
            .onFailure { logger.warn("Failed to inspect the run-at-startup registration", it) }
            .getOrNull() ?: return
        if (actual != desired) applyLive(desired)
    }
}
