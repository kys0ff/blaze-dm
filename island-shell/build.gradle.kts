plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.jetbrainsCompose)
}

group = "org.island"
version = "1.0.0"

repositories {
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

dependencies {
    api(project(":island-foundation"))
    api(project(":island-theme-api"))
    api(project(":island-theme-ui"))
    api(project(":island-ui"))

    api(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }
    // The shell owns the Jewel DecoratedWindow (custom title bar + window controls).
    api(libs.jewel.int.ui.standalone)
    api(libs.jewel.int.ui.decorated.window)
    implementation(libs.intellij.platform.icons)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}
