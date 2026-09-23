package org.blaze.platform.autostart

/**
 * Contract for registering the app to run when the user logs in. Each desktop platform
 * has its own mechanism (XDG autostart file, registry Run key, LaunchAgent plist);
 * implementations translate [enable]/[disable] into it and report the live OS state
 * through [isEnabled] so callers can reconcile it with the persisted setting.
 */
interface AutoStartService {
    /** Whether this platform offers any autostart mechanism we can drive. */
    val isSupported: Boolean

    /** Whether the OS currently has the app registered to start at login. */
    fun isEnabled(): Boolean

    fun enable(): Result<Unit>

    fun disable(): Result<Unit>

    /**
     * The command the OS registration currently launches, or null when nothing is registered (or the
     * platform cannot read it back). Reporting it is what lets [AutoStartController] notice a login
     * item whose program has since been uninstalled - or one a debug session wrote by accident.
     */
    fun registrationCommand(): String? = null
}
