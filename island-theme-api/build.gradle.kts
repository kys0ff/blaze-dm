plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "org.island"
version = "1.0.0"

dependencies {
    // The palette/plugin contract layer is Compose-free on purpose: theme plugin jars must be
    // buildable against this module alone, with colors as packed ARGB Longs.
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.slf4j.simple)
}

kotlin {
    jvmToolchain(25)
}
