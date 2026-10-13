plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.jetbrainsCompose)
}

group = "org.island"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

dependencies {
    // The Jewel bridge layer: turns an island-theme-api palette into a live Jewel/Compose theme.
    api(project(":island-theme-api"))
    implementation(project(":island-foundation"))

    // Compose Desktop without Material — Jewel replaces it entirely in the Islands design system.
    api(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }
    api(libs.jewel.int.ui.standalone)
    // IslandTheme installs the invisible-title-bar decorated-window styling into its public API.
    api(libs.jewel.int.ui.decorated.window) {
        exclude(group = "org.jetbrains.compose.material")
    }
    implementation(libs.intellij.platform.icons)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}
