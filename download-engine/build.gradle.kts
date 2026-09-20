plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.4.20"
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    
    // Ktor for HTTP downloads
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    
    // BitTorrent
    implementation("com.github.atomashpolskiy:bt-core:1.10")
    implementation("com.github.atomashpolskiy:bt-http-tracker-client:1.10")
    implementation("com.github.atomashpolskiy:bt-dht:1.10")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}

kotlin {
    jvmToolchain(21)
}
