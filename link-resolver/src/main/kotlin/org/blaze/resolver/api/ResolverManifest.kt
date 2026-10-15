package org.blaze.resolver.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Declarative descriptor every link-handler plugin jar must ship at
 * `META-INF/blaze/resolver.json` inside its own `src/main/resources`.
 *
 * The manifest is the *authoritative* source of a handler's identity and UI metadata:
 * Blaze reads it straight from the jar **without instantiating (or even opening a class
 * loader over) any plugin code**, so the app can show a handler's name, author, icon and
 * claimed URL patterns in the picker, the Extensions panel and the install-consent prompt
 * before a single line of third-party code runs.
 *
 * Built-in handlers declare the same shape in code (see their companion `Manifest`) so the
 * UI has exactly one metadata source for every handler, plugin or built-in.
 *
 * @property manifestVersion Schema version of this file; Blaze rejects newer versions it
 *   cannot understand instead of misreading them.
 * @property id Stable, unique persistence key (`[a-z0-9][a-z0-9_-]*`). Enable/disable
 *   state, options and priority are stored under it — choose once, never change.
 * @property displayName Human-readable name shown in the picker and settings.
 * @property description Short one-line explanation of what this handler resolves.
 * @property version The handler's own version, informational (shown as a byline).
 * @property author Author credit, shown as a byline.
 * @property homepage Optional project page; offered as a link where the UI shows details.
 * @property license Optional SPDX-style license identifier.
 * @property issuesUrl Optional support/issues link.
 * @property mainClass FQCN of the [LinkResolver] implementation to instantiate directly.
 *   When absent, providers are discovered through `META-INF/services`
 *   ([java.util.ServiceLoader]) as usual.
 * @property icons Resource paths (relative to the jar root) of the handler's icon in any
 *   of PNG / SVG, with optional dark-theme variants; SVG is preferred when present.
 * @property urlPatterns Case-insensitive globs over the full URL where `*` matches any
 *   run of characters — e.g. a scheme, host suffix and path each separated by wildcards.
 *   When non-empty they act as a **hard pre-gate**: the
 *   plugin's [LinkResolver.canResolve] is never called for a URL none of the patterns
 *   match — less untrusted code runs and the user sees exactly what the handler claims.
 * @property defaultPriority Ordering weight applied until the user overrides it; higher
 *   runs first among matching handlers. Null means 0.
 * @property minAppVersion Lowest Blaze version this handler supports; jars requiring a
 *   newer app are skipped with a readable reason.
 */
@Serializable
data class ResolverManifest(
    val manifestVersion: Int = CURRENT_VERSION,
    val id: String,
    val displayName: String,
    val description: String = "",
    val version: String = "0.0.0",
    val author: String = "",
    val homepage: String? = null,
    val license: String? = null,
    val issuesUrl: String? = null,
    val mainClass: String? = null,
    val icons: ResolverIconSpec = ResolverIconSpec(),
    val urlPatterns: List<String> = emptyList(),
    val defaultPriority: Int? = null,
    val minAppVersion: String? = null,
) {
    /** True when this handler may be asked about [url] per its declared patterns (empty = unrestricted). */
    fun matches(url: String): Boolean =
        urlPatterns.isEmpty() || compiledPatterns().any { it.matches(url) }

    private fun compiledPatterns(): List<Regex> = urlPatterns.map { globToRegex(it) }

    companion object {
        /** The manifest schema version this build of Blaze understands. */
        const val CURRENT_VERSION: Int = 1

        /** Conventional resource path of the manifest inside a plugin jar. */
        const val PATH: String = "META-INF/blaze/resolver.json"

        private val logger = LoggerFactory.getLogger(ResolverManifest::class.java)

        /** Ids are persistence keys: lower-case, start alphanumeric, then `[a-z0-9_-]`. */
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Parses and validates manifest text. Returns null — never throws — for blank,
         * malformed, schema-too-new or invalid content; an unreadable manifest must degrade
         * to "jar rejected", not to a half-described handler.
         */
        fun parse(text: String?): ResolverManifest? {
            if (text.isNullOrBlank()) return null
            val manifest = runCatching { json.decodeFromString<ResolverManifest>(text) }
                .onFailure { logger.warn("Malformed resolver manifest JSON", it) }
                .getOrNull() ?: return null
            return manifest.takeIf { it.isValid() }
        }

        /** Reads `META-INF/blaze/resolver.json` from a jar on disk, without touching its classes. */
        fun load(jarPath: Path): ResolverManifest? = runCatching {
            ZipFile(jarPath.toFile()).use { zip ->
                zip.getEntry(PATH)?.let { entry ->
                    zip.getInputStream(entry).use(InputStream::readBytes).decodeToString()
                }
            }
        }.onFailure { logger.warn("Failed to read resolver manifest from {}", jarPath, it) }
            .getOrNull()
            ?.let(::parse)

        /** Reads the manifest through a class loader (built-ins: from the module's own resources). */
        fun load(classLoader: ClassLoader): ResolverManifest? = runCatching {
            classLoader.getResourceAsStream(PATH)?.use { it.readBytes().decodeToString() }
        }.getOrNull()?.let(::parse)

        /** True when [candidate] is a valid `[a-z0-9][a-z0-9_-]*` persistence key. */
        fun isValidId(candidate: String): Boolean = ID_PATTERN.matches(candidate)

        /**
         * Numeric-segment version comparison (`"1.4.0"` vs `"1.3.9"`); non-numeric tails
         * are ignored, missing segments count as 0. Returns true when [have] >= [need].
         */
        fun versionAtLeast(have: String, need: String): Boolean {
            val a = have.takeWhile { it.isDigit() || it == '.' }.split('.').map { it.toIntOrNull() ?: 0 }
            val b = need.takeWhile { it.isDigit() || it == '.' }.split('.').map { it.toIntOrNull() ?: 0 }
            val size = maxOf(a.size, b.size)
            for (i in 0 until size) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return true
        }

        /**
         * Compiles a case-insensitive `*` glob into an anchored regex. Only `*` is special —
         * no character classes or backreferences, so a hostile pattern cannot blow up
         * matching time (each `*` maps to a linear `.*`).
         */
        fun globToRegex(glob: String): Regex {
            val built = buildString(glob.length + 8) {
                append("^")
                glob.forEach { ch ->
                    when (ch) {
                        '*' -> append(".*")
                        else -> append(Regex.escape(ch.toString()))
                    }
                }
                append("$")
            }
            return Regex(built, RegexOption.IGNORE_CASE)
        }
    }

    private fun isValid(): Boolean =
        manifestVersion <= CURRENT_VERSION &&
                isValidId(id) &&
                displayName.isNotBlank() &&
                icons.isSane() &&
                (minAppVersion == null || minAppVersion.isNotBlank())
}

/**
 * Icon assets shipped inside the handler's jar, as resource paths relative to the jar
 * root (a leading `/` is accepted and normalized away). At least one of the light variants
 * should be provided; dark variants are used when the app runs in dark mode.
 */
@Serializable
data class ResolverIconSpec(
    val png: String? = null,
    val svg: String? = null,
    val pngDark: String? = null,
    val svgDark: String? = null,
) {
    /** Every declared path must be a plain relative resource path — no traversal. */
    fun isSane(): Boolean = listOf(png, svg, pngDark, svgDark).all { path ->
        path == null || (path.isNotBlank() && !path.contains(".."))
    }

    fun normalized(path: String): String = path.removePrefix("/")
}
