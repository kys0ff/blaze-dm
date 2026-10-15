plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
}

/**
 * Blaze command-line client.
 *
 * It is a pure client of the public API: it discovers the running downloader service (or the service a
 * `blaze serve` invocation started) and speaks the same loopback protocol a browser bridge would use. It
 * depends on the API *contracts* only - never on the engine, never on the Compose GUI - so it works on a
 * headless box and cannot drift from the engine's internals.
 */
dependencies {
    implementation(project(":download-api"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // Integration tests spin a real local service (engine + HTTP transport) to drive the CLI end to end.
    testImplementation(project(":download-service"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("org.blaze.cli.MainKt")
}
