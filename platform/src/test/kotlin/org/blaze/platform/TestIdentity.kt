package org.blaze.platform

import org.blaze.platform.api.PlatformIdentity

/**
 * A representative app identity used across the module's tests; deliberately shaped
 * like Blaze's so assertions read the way a real desktop entry does.
 */
val TEST_IDENTITY: PlatformIdentity = PlatformIdentity(
    appId = "org.blaze",
    appName = "Blaze",
    description = "Blaze download manager",
    genericName = "Download Manager",
    categories = "Network;FileTransfer;",
    mainClass = "org.blaze.MainKt",
    executableName = "blaze",
)
