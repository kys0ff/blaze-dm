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
include(":link-resolver")
include(":theming")
include(":gui")
include(":i18n")
include(":filepicker")
