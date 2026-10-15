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
 * The stable, client-facing download contract.
 *
 * This module intentionally knows nothing about the engine, Ktor, Compose or any transport: it only
 * declares serializable data shapes and the [org.blaze.download.api.DownloadsApi] interface. GUI, CLI
 * and future external clients all compile against this boundary, so nothing below it can leak upward.
 */
dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(25)
}
