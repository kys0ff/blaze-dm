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
include(":theming")
include(":tray")
include(":platform")
include(":gui")
include(":i18n")
include(":filepicker")
