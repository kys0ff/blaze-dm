plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
}

/**
 * The platform-neutral OS-integration layer.
 *
 * Everything the desktop launcher needs before a window exists lives here: translating raw process
 * arguments / native open events into [org.blaze.osintegration.ExternalOpenRequest]s, validating them
 * as untrusted input, buffering them across startup, and forwarding them from a second launcher process
 * to the already-running instance over a token-authenticated local IPC channel.
 *
 * It deliberately depends only on the public [org.blaze.download.api] module: no engine, no Compose, no
 * transport server. The GUI (and, later, any headless host) wires these pieces to its own
 * [org.blaze.download.api.DownloadsApi]; this module stays a pure, testable JVM library.
 */
dependencies {
    api(project(":download-api"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.slf4j.simple)
}

kotlin {
    jvmToolchain(25)
}
