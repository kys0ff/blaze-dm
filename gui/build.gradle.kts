import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.jetbrainsCompose)
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":download-engine"))
    implementation(project(":link-resolver"))
    implementation(project(":theming"))
    implementation(project(":tray"))
    implementation(project(":i18n"))
    implementation(project(":filepicker"))
    
    // Compose Desktop, without Compose Material — Jewel replaces it entirely.
    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }

    // Jewel: the IntelliJ-style ("Int UI") theme + component set for standalone
    // Compose for Desktop apps.
    implementation(libs.jewel.int.ui.standalone)
    implementation(libs.jewel.int.ui.decorated.window)
    implementation(libs.intellij.platform.icons)

    // Voyager Navigation
    implementation(libs.voyager.navigator)
    implementation(libs.voyager.screenmodel)
    implementation(libs.voyager.transitions)
    implementation(libs.voyager.koin)

    // Koin
    implementation(libs.koin.core)
    implementation(libs.koin.compose)

    // App-shell settings (app.json) are serialized locally in this module.
    implementation(libs.kotlinx.serialization.json)

    // Coroutines Swing for Dispatchers.Main on Desktop
    implementation(libs.kotlinx.coroutines.swing)

    // Logging: logback is configured and controlled at runtime by org.blaze.logging.LogConfigurator.
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

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
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Deb
            )

            packageName = "org.blaze"
            packageVersion = "1.0.0"

            linux {
                iconFile = rootProject.file("src/main/resources/app-icon.png")
            }

            windows {
                iconFile = rootProject.file("src/main/resources/app-icon.ico")
            }

            macOS {
                iconFile = rootProject.file("src/main/resources/app-icon.icns")
            }
        }
    }
}