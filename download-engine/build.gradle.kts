plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Logging
    implementation(libs.slf4j.api)
    
    // Ktor for HTTP downloads
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    
    // BitTorrent
    implementation(libs.bt.core)
    implementation(libs.bt.http.tracker.client)
    implementation(libs.bt.dht)
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.slf4j.simple)
}

kotlin {
    jvmToolchain(21)
}

/**
 * Throughput benchmarks live in the test source set because they need the fake HTTP server, but
 * they are not part of `check`: their numbers depend on the machine. Run with
 * `./gradlew :download-engine:httpBenchmark`.
 */
tasks.register<Test>("httpBenchmark") {
    group = "verification"
    description = "Runs the HTTP throughput benchmarks and prints before/after numbers."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("blaze.benchmark", "true")
    outputs.upToDateWhen { false }

    filter {
        includeTestsMatching("org.blaze.engine.benchmark.*")
    }
}

tasks.named<Test>("test") {
    exclude("org/blaze/engine/benchmark/**")
    // The transfer tests hold multi-megabyte payloads in memory on purpose.
    maxHeapSize = "1g"
}
