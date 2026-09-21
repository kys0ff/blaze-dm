package org.blaze.resolver.builtin

import org.blaze.resolver.api.LinkResolver
import org.blaze.resolver.api.ResolvedLink
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
 */
class MediafireResolver : LinkResolver {

    override val id: String = "mediafire"
    override val displayName: String = "MediaFire"
    override val description: String =
        "Resolves MediaFire share pages into a direct download link"

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    override fun canResolve(url: String): Boolean =
        parseHost(url)?.let(::isMediafireShareHost) == true

    override fun resolve(url: String): ResolvedLink {
        val normalizedUrl = url.trim()
        val html = fetchPage(normalizedUrl)

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

    private fun fetchPage(url: String): String {
        val request = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
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

    private companion object {
        const val USER_AGENT =
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