package org.blaze.resolver.builtin.mediafire

import org.blaze.resolver.api.ConfigurableLinkResolver
import org.blaze.resolver.api.ResolvedLink
import org.blaze.resolver.api.ResolverConfig
import org.blaze.resolver.api.ResolverContext
import org.blaze.resolver.api.ResolverIconSpec
import org.blaze.resolver.api.ResolverManifest
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.api.integerOption
import org.blaze.resolver.api.textOption
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Built-in handler for MediaFire share pages.
 *
 * Resolution is composed of small extraction helpers:
 *
 *   page -> direct URL
 *        -> filename
 *        -> size
 *        -> ResolvedLink
 *
 * It demonstrates the configurable facet: two user-editable options (a request user agent
 * and a per-handler timeout) declared through [optionDescriptors] and folded into the
 * resolve via [applySettings]; the subsystem-wide [ResolverContext.requestTimeoutSeconds]
 * backs the timeout when the handler's own option is left at its default.
 */
class MediafireResolver : ConfigurableLinkResolver {

    override val optionDescriptors: List<ResolverOption> = listOf(
        textOption(
            key = OPTION_USER_AGENT,
            label = "Request user agent",
            description = "User-Agent header sent while reading the share page.",
            default = DEFAULT_USER_AGENT,
        ),
        integerOption(
            key = OPTION_TIMEOUT,
            label = "Request timeout (seconds)",
            description = "How long a single page fetch may take before it is abandoned.",
            range = TIMEOUT_RANGE,
            default = 15,
        ),
    )

    @Volatile
    private var config: ResolverConfig = ResolverConfig()

    override fun settings(): ResolverConfig = config

    override fun applySettings(settings: ResolverConfig) {
        config = settings
    }

    override fun canResolve(url: String): Boolean =
        parseHost(url)?.let(::isMediafireShareHost) == true

    override fun resolveAll(url: String, context: ResolverContext): List<ResolvedLink> =
        listOf(resolveWith(url, effectiveUserAgent(), effectiveTimeout(context)))

    // The legacy single-link entry point; retained so the handler still satisfies LinkResolver
    // directly (the registry always routes through resolveAll, but tests and callers may not).
    override fun resolve(url: String): ResolvedLink =
        resolveWith(url, effectiveUserAgent(), DEFAULT_TIMEOUT_SECONDS.toInt())

    private fun effectiveUserAgent(): String =
        config.value(OPTION_USER_AGENT, DEFAULT_USER_AGENT)

    private fun effectiveTimeout(context: ResolverContext): Int {
        val own = config.intValue(OPTION_TIMEOUT, DEFAULT_TIMEOUT_SECONDS, TIMEOUT_RANGE)
        // A handler timeout left at its default defers to the subsystem preference; an
        // explicit value always wins.
        val seconds = if (own != DEFAULT_TIMEOUT_SECONDS) own
        else context.requestTimeoutSeconds.toLong()
        return seconds.coerceIn(TIMEOUT_RANGE.first, TIMEOUT_RANGE.last).toInt()
    }

    private fun resolveWith(url: String, userAgent: String, timeoutSeconds: Int): ResolvedLink {
        val normalizedUrl = url.trim()
        val html = fetchPage(normalizedUrl, userAgent, timeoutSeconds)

        val directUrl = extractDirectUrl(html)
        val fileName = extractFileName(directUrl)
        val sizeBytes = extractFileSize(html)

        return ResolvedLink(
            directUrl = directUrl,
            fileName = fileName,
            sizeBytes = sizeBytes,
            headers = mapOf("Referer" to normalizedUrl)
        )
    }

    private fun fetchPage(url: String, userAgent: String, timeoutSeconds: Int): String {
        val timeout = Duration.ofSeconds(timeoutSeconds.toLong())
        val client: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(timeout)
            .build()

        val request = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .timeout(timeout)
            .GET()
            .build()

        val response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        )

        require(response.statusCode() in 200..299) {
            "MediaFire page returned HTTP ${response.statusCode()}"
        }

        return response.body()
    }

    private fun extractDirectUrl(html: String): String =
        extractAnchorHref(
            html = html,
            anchorId = "downloadButton"
        ) ?: error("Couldn't find #downloadButton on the MediaFire page")

    /**
     * Finds an <a> element by id and returns its href.
     *
     * Attribute ordering does not matter, so both of these work:
     * ``` html
     *   <a id="downloadButton" href="...">
     *   <a href="..." id="downloadButton">
     * ```
     */
    private fun extractAnchorHref(
        html: String,
        @Suppress("SameParameterValue")
        anchorId: String
    ): String? {
        return ANCHOR_TAG
            .findAll(html)
            .mapNotNull { match ->
                val attributes = match.groupValues[1]

                val id = extractAttribute(
                    attributes = attributes,
                    name = "id"
                ) ?: return@mapNotNull null

                if (!id.equals(anchorId, ignoreCase = true)) {
                    return@mapNotNull null
                }

                extractAttribute(
                    attributes = attributes,
                    name = "href"
                )
            }
            .firstOrNull()
            ?.unescapeHtml()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Extracts a single HTML attribute.
     *
     * Supports:
     * ```html
     *   href="value"
     *   href='value'
     * ```
     */
    private fun extractAttribute(
        attributes: String,
        name: String
    ): String? {
        val regex = Regex(
            """\b${Regex.escape(name)}\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        )

        return regex
            .find(attributes)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractFileName(directUrl: String): String? =
        runCatching {
            URI(directUrl)
                .path
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /**
     * Current MediaFire markup:
     *
     *   Download (x.xxGB)
     *
     * Falls back to older "File size ..." markup.
     */
    private fun extractFileSize(html: String): Long? =
        extractDownloadSize(html)
            ?: extractLegacyFileSize(html)

    private fun extractDownloadSize(html: String): Long? =
        DOWNLOAD_SIZE
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::parseSize)

    private fun extractLegacyFileSize(html: String): Long? =
        FILE_SIZE
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::parseSize)

    private fun parseSize(raw: String): Long? {
        val match = SIZE.find(raw) ?: return null

        val value = match.groupValues[1]
            .replace(",", "")
            .toDoubleOrNull()
            ?: return null

        val multiplier = when (match.groupValues[2].uppercase()) {
            "B" -> 1L
            "KB" -> 1024L
            "MB" -> 1024L * 1024
            "GB" -> 1024L * 1024 * 1024
            "TB" -> 1024L * 1024 * 1024 * 1024
            else -> return null
        }

        return (value * multiplier).toLong()
    }

    private fun parseHost(url: String): String? =
        runCatching {
            URI(url.trim())
                .host
                ?.lowercase()
        }.getOrNull()

    private fun isMediafireShareHost(host: String): Boolean =
        (host == "mediafire.com" || host.endsWith(".mediafire.com")) &&
                !host.startsWith("download")

    companion object {
        /**
         * The built-in's manifest, declared in code exactly like a plugin jar's on-disk
         * `META-INF/blaze/resolver.json` — one metadata source for the whole UI. The icon
         * ships in this module's resources (`link-resolver/src/main/resources`).
         */
        val Manifest: ResolverManifest = ResolverManifest(
            id = "mediafire",
            displayName = "MediaFire",
            description = "Resolves MediaFire share pages into a direct download link",
            version = "1.0.0",
            author = "Blaze",
            icons = ResolverIconSpec(png = "icons/mediafire.png"),
            urlPatterns = listOf(
                "https://mediafire.com", "https://mediafire.com/*",
                "https://*.mediafire.com", "https://*.mediafire.com/*",
                "http://mediafire.com", "http://mediafire.com/*",
                "http://*.mediafire.com", "http://*.mediafire.com/*",
            ),
        )

        const val OPTION_USER_AGENT = "userAgent"
        const val OPTION_TIMEOUT = "timeoutSeconds"
        const val DEFAULT_TIMEOUT_SECONDS = 15L
        val TIMEOUT_RANGE = 5L..120L

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36"

        /**
         * Captures the attribute section of an <a ...> element.
         */
        val ANCHOR_TAG = Regex(
            """<a\b([^>]*)>""",
            RegexOption.IGNORE_CASE
        )

        val DOWNLOAD_SIZE = Regex(
            """Download\s*\(\s*([0-9.,]+\s*(?:B|KB|MB|GB|TB))\s*\)""",
            RegexOption.IGNORE_CASE
        )

        val FILE_SIZE = Regex(
            """File size[^0-9]*([0-9.,]+\s*(?:B|KB|MB|GB|TB))""",
            RegexOption.IGNORE_CASE
        )

        val SIZE = Regex(
            """([0-9.,]+)\s*(B|KB|MB|GB|TB)""",
            RegexOption.IGNORE_CASE
        )

        fun String.unescapeHtml(): String =
            replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
    }
}