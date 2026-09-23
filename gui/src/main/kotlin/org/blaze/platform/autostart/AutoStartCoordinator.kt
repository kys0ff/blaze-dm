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
 *   drift from a removed autostart entry or a settings file edited by hand, and first
 *   dropping a registration that can no longer launch the app at all (an uninstalled
 *   launcher, or the JDK a debug session happened to run on).
 */
class AutoStartCoordinator(
    private val appSettingsRepository: AppSettingsRepository,
    service: AutoStartService,
) {
    private val controller = AutoStartController(service)

    val isSupported: Boolean get() = controller.isSupported

    fun applyLive(enabled: Boolean): Boolean = controller.applyLive(enabled)

    fun revertToPersisted() = controller.syncTo(appSettingsRepository.persisted.runAtStartup)

    /**
     * Startup reconciliation: first remove a login item that cannot work, then make what remains
     * match the persisted setting. The order matters - pruning alone would leave "run at startup"
     * silently off, while the follow-up sync re-registers the launcher actually running now.
     */
    fun sync() {
        controller.pruneStaleRegistration()
        controller.syncTo(appSettingsRepository.settings.value.runAtStartup)
    }
}
