plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.jetbrainsCompose)
}

group = "org.island"
version = "1.0.0"

dependencies {
    // A consumer's worth of the stack: the demo depends on the island modules only — never on
    // any Blaze module — which is what proves the foundation is genuinely app-agnostic.
    implementation(project(":island-shell"))
    implementation(project(":island-theme-ui"))

    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }
    implementation(libs.jewel.int.ui.standalone)
    implementation(libs.jewel.int.ui.decorated.window)
    implementation(libs.intellij.platform.icons)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

// Jewel is built for Java 25, so the launcher tasks must fork on a JetBrains 25 runtime. Pin BOTH
// javaLauncher and executable (the Compose plugin re-points `executable` at the daemon JVM during
// task-graph setup, which otherwise aborts with an executable-vs-javaLauncher mismatch) — the same
// pattern as :gui.
val jbr25Launcher = project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
    vendor.set(JvmVendorSpec.JETBRAINS)
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(jbr25Launcher)
    doFirst {
        executable(jbr25Launcher.get().executablePath.asFile.absolutePath)
    }
}

compose.desktop {
    application {
        mainClass = "org.island.demo.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "Island Demo"
            packageVersion = "1.0.0"
        }
    }
}
