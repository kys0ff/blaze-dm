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
    implementation(project(":theming"))
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
    // Compose UI tests deliver real pointer and key events to an offscreen scene, which is the only
    // way to reproduce (and then pin) the submenu hover trap: it is an interaction bug, invisible to
    // any test that only calls the state machine directly. The `runDesktopComposeUiTest` these tests use
    // lives in the plain ui-test artifact, so it is specified directly (the uiTestJUnit4 DSL accessor is
    // deprecated and only exists to add a JUnit4 rule none of these tests use).
    testImplementation(libs.compose.ui.test)
}

repositories {
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

kotlin {
    jvmToolchain(21)
}

/**
 * The UI tests compose Jewel widgets, and Jewel 0.41.0 is built for Java 25 (class file version 69),
 * which the toolchain above cannot load. Running the tests on the same JetBrains 25 launcher the
 * screenshot harness uses keeps the whole presentational layer testable.
 */
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.JETBRAINS)
        }
    )
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
            // the app needs at runtime must be named here or it is simply absent from the jlink image
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
                // deb/rpm ship a jpackage-generated .desktop with these MimeType entries, and the
                // AppImage/desktop-entry installer honors them the same way (xdg registration).
                fileAssociation(
                    mimeType = "application/x-bittorrent",
                    extension = "torrent",
                    description = "BitTorrent torrent file",
                )
            }

            windows {
                iconFile = file("src/main/resources/app-icon.ico")
                // MSI-level .torrent association (HKCR write during install, cleaned on uninstall).
                // A fixed upgradeUuid makes successive MSIs *replace* the old registration instead
                // of stacking orphan entries. The magnet: URL protocol cannot be declared through
                // jpackage at all, so the packaged launcher registers it per-user at runtime
                // (see org.blaze.osintegration.platform.WindowsUrlSchemeRegistrar in Main.kt).
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
                // -> CFBundleDocumentTypes: Launch Services learns to open .torrent with Blaze.
                fileAssociation(
                    mimeType = "application/x-bittorrent",
                    extension = "torrent",
                    description = "BitTorrent torrent file",
                )
                // jpackage/Compose has no URL-scheme knob, so the raw plist key is injected
                // verbatim into Info.plist: CFBundleURLTypes registers magnet: with
                // Launch Services. Warm opens arrive via MacOpenEventsBridge, not argv.
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

    javaLauncher.set(
        project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.JETBRAINS)
        }
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

    // Jewel 0.41.0 is built for Java 25 (class file version 69); run the harness on a 25 launcher so
    // the offscreen scene can load it, independent of the jvmToolchain(21) the app itself targets.
    javaLauncher.set(
        project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.JETBRAINS)
        }
    )
}