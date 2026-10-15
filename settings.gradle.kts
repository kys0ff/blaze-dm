@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "blaze-dm"

// Vendored `bt` fork (Apache-2.0). bt-bencoding + bt-core are imported here as their own Gradle subprojects
// so Round-2 torrent features can be added in reviewable commits (see third-party/bt/PATCHES.md). Java packages
// stay `bt.*`; only the Maven coordinates are re-namespaced to org.blaze.
include(":third-party:bt:bt-bencoding")
include(":third-party:bt:bt-core")

include(":download-engine")
include(":download-api")
include(":download-service")
include(":os-integration")
include(":cli")
include(":link-resolver")
include(":tray")
include(":platform")
include(":notifications")
include(":gui")
include(":i18n")
include(":filepicker")

// Reusable Islands/Jewel presentation foundation (see docs/ISLAND_MODULES.md).
// Pure layers carry no Compose; UI layers go foundation → theme-ui → ui → shell.
include(":island-foundation")
include(":island-theme-api")
include(":island-theme-ui")
include(":island-ui")
include(":island-shell")
include(":island-demo")
