package org.blaze.platform.autostart

import org.blaze.platform.desktop.LaunchCommand
import org.slf4j.LoggerFactory

/**
 * Generic bridge between a *desired* run-at-startup state and the OS registration,
 * so callers never touch an [AutoStartService] directly:
 *
 * - [applyLive] pushes a desired state to the OS right now;
 * - [syncTo] reconciles the OS with a desired state, touching it only on drift;
 * - [pruneStaleRegistration] drops a registration the desktop can no longer honour.
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

    /**
     * Whether the OS holds a registration for this app right now. Read-only, and never fatal: a
     * platform whose check cannot run reports "not registered" so callers do not act on a guess.
     */
    fun isRegistered(): Boolean = service.isSupported &&
        runCatching { service.isEnabled() }
            .onFailure { logger.warn("Failed to inspect the run-at-startup registration", it) }
            .getOrDefault(false)

    /**
     * Write/remove the OS registration to match [enabled]; failures are logged, never thrown.
     * Returns whether the OS ended up in the requested state (true also when this platform has no
     * mechanism at all, since there is then nothing left to do).
     */
    fun applyLive(enabled: Boolean): Boolean {
        if (!service.isSupported) return true
        val result = if (enabled) service.enable() else service.disable()
        return result
            .onFailure { logger.warn("Failed to update the run-at-startup registration", it) }
            .isSuccess
    }

    /** Touch the OS only when its actual state differs from the desired [enabled] state. */
    fun syncTo(enabled: Boolean) {
        if (!service.isSupported) return
        val actual = runCatching { service.isEnabled() }
            .onFailure { logger.warn("Failed to inspect the run-at-startup registration", it) }
            .getOrNull() ?: return
        if (actual != enabled) applyLive(enabled)
    }

    /**
     * Removes a login item that cannot work any more, which is the residue of two things a desktop
     * never gets over on its own:
     *
     * - an **uninstall or moved install**: the registered program's path is simply gone, so every
     *   sign-in tries to launch something that is not there;
     * - a **debug session**: run-at-startup toggled while launching through a JDK registers that JDK
     *   as the login item, and it fails (silently or loudly) forever after.
     *
     * A registration we cannot read back, or whose command is `PATH`-relative or lives under an
     * ephemeral root (a DMG/AppImage mount), is left alone - those disappear and reappear legitimately.
     * Returns true when the OS was touched; a following [syncTo] then re-registers what is really wanted.
     */
    fun pruneStaleRegistration(): Boolean {
        if (!service.isSupported) return false
        val command = runCatching { service.registrationCommand() }
            .onFailure { logger.warn("Failed to inspect the run-at-startup registration", it) }
            .getOrNull() ?: return false

        val unlaunchable = !LaunchCommand.isStandaloneExecutable(command)
        val missing = LaunchCommand.isMissingInstall(command)
        if (!unlaunchable && !missing) return false

        logger.info(
            "Dropping the run-at-startup registration: {} ({})",
            command,
            if (missing) "its launcher no longer exists" else "a development launch, not a standalone launcher",
        )
        return service.disable()
            .onFailure { logger.warn("Failed to remove the stale run-at-startup registration", it) }
            .isSuccess
    }
}
