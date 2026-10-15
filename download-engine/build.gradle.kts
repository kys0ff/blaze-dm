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
    // Production HTTP engine. HttpEngineBenchmark + docs/PROGRESS.md Phase 3 sect.1 measured
    // Java (the previous pick) vs CIO vs OkHttp on the same 32 MiB segmented workload: OkHttp
    // wins every unthrottled cell (1.2-2.1x Java) and matches or beats Java under a per-conn
    // cap, with 3x fewer threads at conn=16 (69 vs 214) and about half the GC pause. CIO is
    // disqualified by opening a fresh socket per request (serverConnections=34 at conn=4),
    // the same reason it was previously not the production pick.
    implementation(libs.ktor.client.okhttp)
    
    // BitTorrent. bt-core + bt-bencoding are the vendored fork (see third-party/bt/PATCHES.md). The DHT and
    // HTTP-tracker modules stay on their upstream artifacts (they wrap native/shaded code and hold no Round-2
    // feature), but each excludes the upstream bt-core/bencoding so they bind to the fork's identical `bt.*` API.
    implementation(project(":third-party:bt:bt-core"))
    implementation(libs.bt.http.tracker.client) {
        exclude(group = "com.github.atomashpolskiy", module = "bt-core")
        exclude(group = "com.github.atomashpolskiy", module = "bt-bencoding")
    }
    implementation(libs.bt.dht) {
        exclude(group = "com.github.atomashpolskiy", module = "bt-core")
        exclude(group = "com.github.atomashpolskiy", module = "bt-bencoding")
    }
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // HttpEngineBenchmark keeps the Java engine on the test classpath so before/after
    // comparisons against the new production engine (OkHttp) can still be run.
    testImplementation(libs.ktor.client.java)
    testImplementation(libs.slf4j.simple)
}

kotlin {
    jvmToolchain(25)
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
