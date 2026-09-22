package org.blaze.platform

import org.blaze.platform.api.PlatformIdentity

/**
 * Blaze's desktop identity: the single place where the app's shell registrations
 * (XDG desktop file, LaunchAgent label, registry value, WM_CLASS, autostart names)
 * are pinned. Everything else is handled by the app-agnostic `:platform` module.
 */
val BlazePlatformIdentity = PlatformIdentity(
    appId = "org.blaze",
    appName = "Blaze",
    description = "Blaze download manager",
    genericName = "Download Manager",
    categories = "Network;FileTransfer;",
    mainClass = "org.blaze.MainKt",
    executableName = "blaze",
    iconResource = "/app-icon.png",
)
