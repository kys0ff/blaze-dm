import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // These two versions must be kept in sync with each other.
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.0"
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

// The exact Jewel version this template was written against.
// Jewel's version string is "<jewel-version>-<intellij-platform-build>".
// Check https://central.sonatype.com/artifact/org.jetbrains.jewel/jewel-int-ui-standalone
// for newer releases.
val jewelVersion = "0.41.0-262.10968.63"
val voyagerVersion = "2.2.21-1.10.3"
val koinVersion = "4.2.2"

dependencies {
    implementation(project(":download-engine"))
    implementation(project(":i18n"))
    implementation(project(":filepicker"))
    
    // Compose Desktop, without Compose Material — Jewel replaces it entirely.
    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }

    // Jewel: the IntelliJ-style ("Int UI") theme + component set for standalone
    // Compose for Desktop apps.
    implementation("org.jetbrains.jewel:jewel-int-ui-standalone:$jewelVersion")

    // Optional: adds DecoratedWindow, for an IDE-style custom title bar.
    // This only renders correctly when running on the JetBrains Runtime (JBR) —
    // see the README for how to switch to it.
    implementation("org.jetbrains.jewel:jewel-int-ui-decorated-window:$jewelVersion")

    implementation("com.jetbrains.intellij.platform:icons:262.10315.125")

    // Voyager Navigation
    implementation("cafe.adriel.voyager:voyager-navigator:$voyagerVersion")
    implementation("cafe.adriel.voyager:voyager-screenmodel:$voyagerVersion")
    implementation("cafe.adriel.voyager:voyager-transitions:$voyagerVersion")
    implementation("cafe.adriel.voyager:voyager-koin:$voyagerVersion")

    // Koin
    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation("io.insert-koin:koin-compose:$koinVersion")

    // Coroutines Swing for Dispatchers.Main on Desktop
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")

    // Logging Implementation
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(kotlin("test"))
}

repositories {
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "org.blaze.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.blaze"
            packageVersion = "1.0.0"
        }
    }
}
