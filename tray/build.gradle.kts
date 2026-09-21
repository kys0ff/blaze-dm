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
}

dependencies {
    // Compose wrappers (TrayHost) compile against desktop Compose; the backends
    // themselves never require it.
    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }

    implementation(libs.koin.core)

    // The Linux backend speaks StatusNotifierItem over D-Bus (AWT's legacy XEmbed
    // tray is broken on KDE/GNOME); pure JVM, no native libraries.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native.unixsocket)

    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}
