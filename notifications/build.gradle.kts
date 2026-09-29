plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Backends are pure JVM: D-Bus (Linux), spawned CLI helpers (macOS/Windows). No Compose
    // dependency on purpose - the library stays copy-able into any JVM/desktop project.
    implementation(libs.koin.core)

    // The Linux backend speaks org.freedesktop.Notifications over D-Bus.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native.unixsocket)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(21)
}
