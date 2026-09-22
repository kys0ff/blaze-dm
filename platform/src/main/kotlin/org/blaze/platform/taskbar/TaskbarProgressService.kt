package org.blaze.platform.taskbar

/**
 * Contract for surfacing activity (e.g. downloads) on the app's taskbar / dock icon.
 *
 * Each desktop offers its own mechanism (KDE/GNOME read the
 * `com.canonical.Unity.LauncherEntry` D-Bus protocol; macOS uses `java.awt.Taskbar`).
 * Implementations translate [setProgress]/[clear] into it and, like the rest of the
 * platform services, degrade quietly when the environment lacks the capability -
 * [isSupported] is then false and every call is a no-op, so callers never need
 * their own platform checks.
 */
interface TaskbarProgressService {
    /** Whether this platform/environment can actually render a taskbar progress bar. */
    val isSupported: Boolean

    /**
     * Show (or update) the progress bar at [progress], a fraction in `0f..1f`.
     * Values outside that range are clamped. No-op when unsupported.
     */
    fun setProgress(progress: Float)

    /** Hide the progress bar, returning the icon to its idle state. No-op when unsupported. */
    fun clear()

    /** Release the underlying desktop-integration resources; safe to call repeatedly. */
    fun dispose()
}
