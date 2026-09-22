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
    // Compose lifecycle wrappers (TaskbarProgressHost, AutoStartHost, locals)
    // compile against desktop Compose; the services themselves never require it.
    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }

    implementation(libs.koin.core)

    // The Linux taskbar backend publishes progress over D-Bus via the
    // com.canonical.Unity.LauncherEntry protocol (KDE Plasma / GNOME read it).
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native.unixsocket)

    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}
