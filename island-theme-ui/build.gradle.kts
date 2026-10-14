import java.net.URL
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.jetbrainsCompose)
}

group = "org.island"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

dependencies {
    // The Jewel bridge layer: turns an island-theme-api palette into a live Jewel/Compose theme.
    api(project(":island-theme-api"))
    implementation(project(":island-foundation"))

    // Compose Desktop without Material — Jewel replaces it entirely in the Islands design system.
    api(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }
    api(libs.jewel.int.ui.standalone)
    // IslandTheme installs the invisible-title-bar decorated-window styling into its public API.
    api(libs.jewel.int.ui.decorated.window) {
        exclude(group = "org.jetbrains.compose.material")
    }
    implementation(libs.intellij.platform.icons)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

// ── Bundled font acquisition ────────────────────────────────────────────────────────────────────
// Blaze ships a handful of free, redistribution-licensed families of its own so the Settings font
// dropdown offers more than the two Jewel bundles (Inter, JetBrains Mono). These are OFL / Apache-2.0
// fonts pulled from their authors' official release archives; the task drops the static TTF faces
// (plus each family's license) under src/main/resources/fonts/bundled/<slug>/ so they become ordinary
// classpath resources the theme loads exactly like Jewel's own. Re-running it is idempotent.
//
// Kept out-of-band (not part of `build`) so a normal compile never touches the network: run
// `gradlew :island-theme-ui:downloadBundledFonts` to (re)fetch the files. Once committed, the
// checked-in TTFs are the source of truth and no download is needed to build.
tasks.register("downloadBundledFonts") {
    group = "resources"
    description = "Downloads the bundled free-font TTFs (OFL / Apache-2.0) into src/main/resources."
    notCompatibleWithConfigurationCache("fetches fonts over the network; run explicitly, never as part of a build")

    // Everything (including the family table) is built inside doLast so no script-local type is
    // captured into the task graph. Each family is (slug, url, faces, licenseEntry) where faces are
    // (zip base name to match, canonical file name to write).
    doLast {
        val destination = layout.projectDirectory.dir("src/main/resources/fonts/bundled").asFile
        val families: List<Triple<String, String, List<Pair<String, String>>>> = listOf(
            Triple(
                "roboto",
                "https://github.com/googlefonts/roboto-2/releases/download/v2.138/roboto-unhinted.zip",
                listOf(
                    "Roboto-Regular.ttf" to "Roboto-Regular.ttf",
                    "Roboto-Bold.ttf" to "Roboto-Bold.ttf",
                    "Roboto-Italic.ttf" to "Roboto-Italic.ttf",
                    "Roboto-BoldItalic.ttf" to "Roboto-BoldItalic.ttf",
                ),
            ),
            Triple(
                "ibm_plex_sans",
                "https://github.com/IBM/plex/releases/download/%40ibm%2Fplex-sans%401.1.0/ibm-plex-sans.zip",
                listOf(
                    "IBMPlexSans-Regular.ttf" to "IBMPlexSans-Regular.ttf",
                    "IBMPlexSans-Bold.ttf" to "IBMPlexSans-Bold.ttf",
                    "IBMPlexSans-Italic.ttf" to "IBMPlexSans-Italic.ttf",
                    "IBMPlexSans-BoldItalic.ttf" to "IBMPlexSans-BoldItalic.ttf",
                ),
            ),
            Triple(
                "source_code_pro",
                "https://github.com/adobe-fonts/source-code-pro/releases/download/2.042R-u%2F1.062R-i%2F1.026R-vf/TTF-source-code-pro-2.042R-u_1.062R-i.zip",
                listOf(
                    "SourceCodePro-Regular.ttf" to "SourceCodePro-Regular.ttf",
                    "SourceCodePro-Bold.ttf" to "SourceCodePro-Bold.ttf",
                    "SourceCodePro-It.ttf" to "SourceCodePro-Italic.ttf",
                    "SourceCodePro-BoldIt.ttf" to "SourceCodePro-BoldItalic.ttf",
                ),
            ),
        )

        // Families whose release archive ships no license file (Source Code Pro's TTF-only zip), so
        // the OFL text is fetched straight from the author's repo instead. Keyed by slug.
        val externalLicenses: Map<String, String> = mapOf(
            "source_code_pro" to "https://raw.githubusercontent.com/adobe-fonts/source-code-pro/release/LICENSE.md",
        )

        families.forEach { (slug, url, faces) ->
            val folder = destination.resolve(slug).also { it.mkdirs() }
            logger.lifecycle("Fetching $slug from $url")
            val bytes = URL(url).openStream().use { it.readBytes() }
            var licenseWritten = false
            ZipInputStream(bytes.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val base = entry.name.substringAfterLast('/')
                        val canonical = faces.firstOrNull { it.first == base }?.second
                        val isLicense =
                            base == "LICENSE.txt" || base == "OFL.txt" || base == "LICENSE" || base == "LICENSE.md"
                        if (canonical != null || isLicense) {
                            val out = folder.resolve(canonical ?: "LICENSE.txt")
                            out.writeBytes(zip.readBytes())
                            if (isLicense) licenseWritten = true
                            logger.lifecycle("  wrote ${out.name} (${out.length()} bytes)")
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (!licenseWritten) {
                val licenseUrl = externalLicenses[slug]
                requireNotNull(licenseUrl) {
                    "$slug: archive carried no license and no externalLicenses entry is configured"
                }
                val out = folder.resolve("LICENSE.txt")
                URL(licenseUrl).openStream().use { out.writeBytes(it.readBytes()) }
                logger.lifecycle("  wrote ${out.name} from $licenseUrl (${out.length()} bytes)")
            }
            val written = folder.listFiles()?.count { it.name.endsWith(".ttf") } ?: 0
            require(written >= faces.size) {
                "$slug: expected ${faces.size} faces, found $written - archive layout changed?"
            }
            require(folder.resolve("LICENSE.txt").isFile) { "$slug: license is missing" }
        }
    }
}
