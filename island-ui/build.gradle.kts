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
    api(project(":island-foundation"))
    api(project(":island-theme-api"))
    api(project(":island-theme-ui"))

    api(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }
    api(libs.jewel.int.ui.standalone)
    implementation(libs.intellij.platform.icons)

    // Pure-JVM SVG rasterizer for plugin-supplied icons (SVG resources inside extension jars).
    implementation(libs.jsvg)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.swing)
}

kotlin {
    jvmToolchain(25)
}
