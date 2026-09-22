package org.blaze.platform.api

/**
 * The pieces of an application's identity that desktop shells need. Every platform
 * service in this module is app-agnostic; it takes its app-specific names and ids
 * from a [PlatformIdentity] the host app provides (see the `desktopPlatformModule`
 * Koin factory for one-call wiring).
 *
 * All other registration names are *derived* from [appId]/[mainClass] so a single
 * identity stays internally consistent across autostart entries, launchers and
 * registries.
 */
data class PlatformIdentity(
    /** Reverse-DNS application id, e.g. `org.blaze` — roots the `.desktop`/plist names. */
    val appId: String,
    /** Human-readable product name shown in menus and entries, e.g. `Blaze`. */
    val appName: String,
    /** One-line description used as the desktop-entry `Comment`. */
    val description: String,
    /** Generic functional name, e.g. `Download Manager` (XDG `GenericName`). */
    val genericName: String,
    /** Semicolon-separated XDG categories, e.g. `Network;FileTransfer;`. */
    val categories: String = "Utility;",
    /**
     * Fully qualified main class, e.g. `org.blaze.MainKt`. `java.awt` on X11 derives
     * the window `WM_CLASS` from it (dots replaced by dashes), and the launcher entry
     * must carry that class for the desktop to match windows to our launcher.
     */
    val mainClass: String,
    /**
     * Bare launcher/binary name used as the fallback exec when the running process
     * cannot report its own command (e.g. `blaze`).
     */
    val executableName: String = appId.substringAfterLast('.'),
) {
    init {
        require(APP_ID_PATTERN.matches(appId)) { "appId must be a reverse-DNS id like 'org.blaze', got '$appId'" }
    }

    /** XDG desktop-entry file name, e.g. `org.blaze.desktop`. */
    val desktopFileName: String get() = "$appId.desktop"

    /** Canonical LauncherEntry URI Plasma resolves progress against. */
    val launcherUri: String get() = "application://$desktopFileName"

    /** D-Bus object path used as the signal source for LauncherEntry updates. */
    val launcherObjectPath: String get() = "/" + appId.split('.').joinToString("/") + "/Launcher"

    /** macOS LaunchAgent label for the autostart plist. */
    val launchAgentLabel: String get() = "$appId.autostart"

    /** X11 `WM_CLASS` of the app window, derived from [mainClass] the way AWT does. */
    val windowManagerClass: String get() = mainClass.replace('.', '-')

    private companion object {
        val APP_ID_PATTERN = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+")
    }
}
