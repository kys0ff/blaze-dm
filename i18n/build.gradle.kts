plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("io.insert-koin:koin-core:4.2.2")
    implementation("io.insert-koin:koin-compose:4.2.2")
}

kotlin {
    jvmToolchain(21)
}
