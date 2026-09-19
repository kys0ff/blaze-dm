plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.0"
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

val jewelVersion = "0.41.0-262.10968.63"
val voyagerVersion = "2.2.21-1.10.3"
val koinVersion = "4.2.2"

dependencies {
    implementation(project(":i18n"))

    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }

    implementation("org.jetbrains.jewel:jewel-int-ui-standalone:$jewelVersion")
    implementation("com.jetbrains.intellij.platform:icons:262.10315.125")

    implementation("cafe.adriel.voyager:voyager-navigator:$voyagerVersion")
    implementation("cafe.adriel.voyager:voyager-screenmodel:$voyagerVersion")
    implementation("cafe.adriel.voyager:voyager-transitions:$voyagerVersion")
    implementation("cafe.adriel.voyager:voyager-koin:$voyagerVersion")

    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation("io.insert-koin:koin-compose:$koinVersion")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}
