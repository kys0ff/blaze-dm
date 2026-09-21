plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.jetbrainsCompose)
}

group = "org.blaze"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

dependencies {
    implementation(project(":i18n"))

    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }

    implementation(libs.jewel.int.ui.standalone)
    implementation(libs.intellij.platform.icons)

    implementation(libs.voyager.navigator)
    implementation(libs.voyager.screenmodel)
    implementation(libs.voyager.transitions)
    implementation(libs.voyager.koin)

    implementation(libs.koin.core)
    implementation(libs.koin.compose)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}
