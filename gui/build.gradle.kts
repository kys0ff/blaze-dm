@file:Suppress("UnstableApiUsage")

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
    // The GUI talks to the engine only through the application-level download API now.
    implementation(project(":download-api"))
    implementation(project(":download-service"))
    // OS-integration layer: external-open parsing + single-instance IPC (never the engine directly).
    implementation(project(":os-integration"))
    implementation(project(":link-resolver"))
    // Theme contract + registry now live in the reusable :island-theme-api module.
    implementation(project(":island-theme-api"))
    // The Jewel theme bridge (IslandTheme, chrome colors, layout metrics) lives in :island-theme-ui.
    implementation(project(":island-theme-ui"))
    // Reusable Islands components (menus, dialogs, toolbar buttons, keybindings).
    implementation(project(":island-ui"))
    // Shared Islands/Jewel foundation (OS switch, native chrome, dialog registry).
    implementation(project(":island-foundation"))
    // Island app-shell scaffolding (IslandWindow, title bar, system menu, drop target).
    implementation(project(":island-shell"))
    implementation(project(":tray"))
    implementation(project(":platform"))
    implementation(project(":notifications"))
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

    // Pure-JVM SVG rasterizer: extension icons declared as SVG in plugin jar resources are
    // rendered through this (Skia's makeFromEncoded decodes rasters only).
    implementation(libs.jsvg)

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
    testImplementation(libs.compose.ui.test)
}

repositories {
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

kotlin {
    jvmToolchain(25)
}

// Jewel 0.41.0 is built for Java 25 (class file version 69), so every JVM that runs the presentational
// layer - the `run`/`runDistributable`/`hotRun` app-launch tasks, the UI tests and the offscreen
// screenshot harness - must fork on Java 25. Pinning only `javaLauncher` is not enough: JavaExec also
// carries an `executable` property that defaults to the Gradle daemon JVM, which IntelliJ runs as a
// Java 21 GraalVM, and Gradle then aborts with an "executable vs javaLauncher toolchain mismatch".
// So pin BOTH properties to the same JetBrains 25 runtime below; compilation stays on jvmToolchain(25).
val jbr25Launcher = project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
    vendor.set(JvmVendorSpec.JETBRAINS)
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(jbr25Launcher)
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(jbr25Launcher)
    // The Compose plugin re-points the fork `executable` at the current/daemon JVM during task graph
    // setup (and IntelliJ does the same from its Gradle JVM), which overrides any config-time value and
    // then collides with `javaLauncher` as an "executable vs javaLauncher toolchain mismatch". Re-assert
    // the executable at execution time, after that, so both properties resolve to the same JetBrains 25
    // and the app forks on 25 no matter which JVM the daemon or IDE happens to run on.
    doFirst {
        executable(jbr25Launcher.get().executablePath.asFile.absolutePath)
    }
}

compose.desktop {
    application {
        mainClass = "org.blaze.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        buildTypes {
            release {
                proguard {
                    configurationFiles.from(file("proguard-rules.pro"))
                }
            }
        }

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Deb,
                TargetFormat.AppImage,
                TargetFormat.Rpm,
            )

            packageName = "Blaze"
            packageVersion = "1.0.0"

            // Packaging metadata consumed by jpackage (deb/rpm/msi/dmg/AppImage).
            description = "Blaze — a cross-platform, blazingly fast download manager."
            vendor = "kys0ff"
            copyright = "© 2026 kys0ff. All rights reserved."

            // An explicit module list switches jpackage off its automatic jdeps sweep: *every* JDK module
            // the app needs at runtime must be named here, or it is simply absent from the jlink image
            // (dev runs on a full JDK and never notice).
            // - jdk.httpserver: com.sun.net.httpserver backs the local download API (LocalApiServer);
            //   without it the packaged service dies with NoClassDefFoundError and reports "Stopped".
            // - jdk.security.auth: dbus-java's SASL handshake needs com.sun.security.auth.UnixSystem;
            //   without it the SNI tray silently disappears.
            // - java.net.http: the Ktor/Java client the engine and CLI use.
            modules(
                "java.net.http",
                "jdk.httpserver",
                "jdk.security.auth",
            )

            linux {
                iconFile = file("src/main/resources/app-icon.png")

                fileAssociation(
                    mimeType = "application/x-bittorrent",
                    extension = "torrent",
                    description = "BitTorrent torrent file",
                )
            }

            windows {
                iconFile = file("src/main/resources/app-icon.ico")
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "7dc9b2e1-34f5-4c6a-9b3d-0a2e7b1c8f55"

                fileAssociation(
                    mimeType = "application/x-bittorrent",
                    extension = "torrent",
                    description = "BitTorrent torrent file",
                    iconFile = file("src/main/resources/app-icon.ico"),
                )
            }

            macOS {
                iconFile = file("src/main/resources/app-icon.icns")

                fileAssociation(
                    mimeType = "application/x-bittorrent",
                    extension = "torrent",
                    description = "BitTorrent torrent file",
                )
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                          <dict>
                            <key>CFBundleURLName</key>
                            <string>org.blaze.magnet</string>
                            <key>CFBundleURLSchemes</key>
                            <array>
                              <string>magnet</string>
                            </array>
                          </dict>
                        </array>
                    """.trimIndent()
                }
            }
        }
    }
}

/**
 * `./gradlew :gui:blazeCleanup` runs Blaze's own `--cleanup` mode (see org.blaze.MainKt), which removes
 * the user-level registrations a packaged installation publishes and cannot take back by itself: the
 * `~/.local/share/applications` entry, the hicolor icon, the run-at-startup item, the per-user Windows
 * `magnet:` key, and the single-instance/local-API files in the data directory.
 *
 * Run it after uninstalling Blaze on a development machine, or whenever the desktop menu still offers a
 * Blaze that is no longer there. It never opens a window and never touches downloads, settings or logs.
 *
 * `-PblazeHome=<dir>` redirects `user.home` for this JVM only, so the pass can be pointed at a sandbox
 * directory and exercised - or demonstrated - without a real desktop being at stake.
 */
val blazeCleanup = tasks.register<JavaExec>("blazeCleanup") {
    group = "blaze"
    description = "Remove Blaze's user-level desktop integration and runtime leftovers."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.blaze.MainKt")
    args("--cleanup")

    (project.findProperty("blazeHome") as String?)?.let {
        systemProperty("user.home", it)
    }
}

/**
 * `./gradlew :gui:uiProbe -Pprobe="click 1234 56"` drives the *real* window through java.awt.Robot
 * (move / click / keys / screen capture) against the live display, with no test hooks in the app. It
 * exists because the Jewel `DecoratedWindow` title bar - and the native popup surfaces menus open in
 * - are never part of a Compose test scene, so title-bar behavior can only be verified on screen.
 *
 * The app must already be running (`:gui:run`). `-Pprobe="shot file.png"` captures the whole screen.
 */
val uiProbe = tasks.register<JavaExec>("uiProbe") {
    group = "blaze"
    description = "Drive and screenshot the real Blaze window (Robot on the live display)."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.blaze.tools.RealWindowDriverKt")
    workingDir = rootDir
    args(
        ((project.findProperty("probe") as String?) ?: "cursor")
            .split(" ").filter { it.isNotBlank() },
    )
}

/**
 * `./gradlew :gui:uiScreenshots` renders the redesigned presentational composables offscreen (Compose
 * `renderComposeScene` on skia software raster - no window, no display) into PNGs under
 * `build/ui-screenshots/`, in both the light and dark Blaze themes, for visual verification.
 */
val uiScreenshots = tasks.register<JavaExec>("uiScreenshots") {
    group = "blaze"
    description = "Render headless UI screenshots (light + dark) into build/ui-screenshots/."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.blaze.tools.ScreenshotHarnessKt")
    systemProperty("java.awt.headless", "true")
    // Write to the repo-root build/ui-screenshots/ regardless of which subproject dir Gradle runs in.
    workingDir = rootDir
}