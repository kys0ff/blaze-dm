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
