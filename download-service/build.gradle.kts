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
 * The download application service: it implements the public [org.blaze.download.api.DownloadsApi]
 * boundary on top of the engine, and hosts the local transport (loopback HTTP) that external clients -
 * the CLI today, a browser bridge tomorrow - connect to.
 *
 * The engine is an `implementation` dependency on purpose: engine types stop at this module's boundary
 * and never appear on a consumer's compile classpath. Consumers see only the public API types. The local
 * HTTP transport uses the JDK's own `com.sun.net.httpserver` (module `jdk.httpserver`), so this adds no
 * server framework to the runtime.
 */
dependencies {
    api(project(":download-api"))
    implementation(project(":download-engine"))

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

application {
    // Headless daemon: boots the engine + local API with no desktop UI.
    mainClass.set("org.blaze.service.headless.HeadlessDownloaderKt")
}
