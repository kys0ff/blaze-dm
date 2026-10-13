plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.jetbrainsCompose)
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

dependencies {
    // Shared Islands/Jewel foundation: OS switch, DWM caption styler, dialog-chrome registry.
    implementation(project(":island-foundation"))
    // Shared Islands theme bridge: layout metrics, chrome colors, theme composables.
    implementation(project(":island-theme-ui"))
    // Reusable Islands components (menus, dialogs, toolbar buttons).
    implementation(project(":island-ui"))
    implementation(project(":i18n"))

    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }

    implementation(libs.jewel.int.ui.standalone)
    implementation(libs.intellij.platform.icons)

    implementation(libs.voyager.navigator)
    implementation(libs.voyager.screenmodel)
    implementation(libs.voyager.transitions)
    implementation(libs.voyager.koin)

    implementation(libs.koin.core)
    implementation(libs.koin.compose)

    // Windows DWM title-bar theming: JNA calls DwmSetWindowAttribute to paint the native
    // caption with the active panel background so dialogs follow the app theme.
    implementation(libs.jna)

    testImplementation(kotlin("test"))
    // The picker's screen model launches into Voyager's `screenModelScope`, which is pinned to the UI
    // dispatcher; the Swing provider makes that resolvable so the real wiring (which directory a picker
    // opens at, and what an accepted pick records) is testable here rather than only by hand.
    testImplementation(libs.kotlinx.coroutines.swing)
}

kotlin {
    jvmToolchain(25)
}
