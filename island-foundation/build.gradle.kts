plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "org.island"
version = "1.0.0"

dependencies {
    // Windows DWM caption integration (JNA → dwmapi); silent no-op on every other OS.
    implementation(libs.jna)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}
