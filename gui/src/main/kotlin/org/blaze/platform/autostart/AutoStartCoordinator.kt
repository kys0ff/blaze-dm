package org.blaze.platform.autostart

import org.blaze.data.AppSettingsRepository

/**
 * Blaze-specific bridge between the persisted `runAtStartup` setting and the OS
 * registration, so neither the settings screen nor the repositories talk to an
 * [AutoStartService] directly. All mechanics live in the `:platform` module's
 * [AutoStartController]; this class only supplies "what the app wants":
 *
 * - [applyLive] pushes a desired state to the OS right now (settings Apply/OK);
 * - [revertToPersisted] rolls the OS back to what is stored on disk, undoing an
 *   unpersisted Apply when the settings screen is disposed;
 * - [sync] reconciles the OS with the persisted setting at app startup, repairing
 *   drift from a removed autostart entry or a settings file edited by hand.
 */
class AutoStartCoordinator(
    private val appSettingsRepository: AppSettingsRepository,
    service: AutoStartService,
) {
    private val controller = AutoStartController(service)

    val isSupported: Boolean get() = controller.isSupported

    fun applyLive(enabled: Boolean) = controller.applyLive(enabled)

    fun revertToPersisted() = controller.syncTo(appSettingsRepository.persisted.runAtStartup)

    fun sync() = controller.syncTo(appSettingsRepository.settings.value.runAtStartup)
}
