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

    /** Whether the OS currently has Blaze registered to start at login. */
    fun isEnabled(): Boolean

    fun enable(): Result<Unit>

    fun disable(): Result<Unit>
}
