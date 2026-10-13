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
