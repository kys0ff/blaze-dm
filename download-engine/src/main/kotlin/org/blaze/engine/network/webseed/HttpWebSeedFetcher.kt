package org.blaze.engine.network.webseed

import bt.metainfo.Torrent
import bt.torrent.webseed.WebSeedFetcher
import bt.torrent.webseed.WebSeedSegment
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.network.HttpNetworkClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Engine-side [WebSeedFetcher] over the shared [HttpNetworkClient], so web-seed bytes ride the exact
 * ranged-GET, redirect and header machinery an ordinary HTTP download uses — no second HTTP stack.
 *
 * Implements the GetRight-style file mapping (BEP-19 `url-list`): each seed URL is treated as a
 * directory root, and a torrent file is fetched at
 * `<seed>/<torrent name>/<file path>` for a multi-file torrent or `<seed>/<file name>` for a
 * single-file one, with a `Range: bytes=<from>-<to>` header covering the file-relative segment the
 * piece resolved to. This is the universally supported subset that also matches the plain HTTP file
 * servers `httpseeds` (BEP-17) are served by in practice; the piece-to-file-range split itself lives
 * in [bt.torrent.webseed.WebSeedPlanner], which this class deliberately does not duplicate.
 */
internal class HttpWebSeedFetcher(
    private val torrent: Torrent,
    private val http: HttpNetworkClient,
    private val headers: Map<String, String> = emptyMap(),
) : WebSeedFetcher {

    override fun fetch(seedUrl: String, segment: WebSeedSegment): ByteArray {
        val url = buildFileUrl(torrent, seedUrl, segment.filePath)
        val request = DownloadRequest.Http(
            name = url.substringAfterLast('/').substringBefore('?').ifBlank { "web-seed" },
            url = url,
            destination = Path.of(".").resolve(segment.filePath.last()),
            headers = headers,
        )
        val expected = segment.length()
        val out = ByteArrayOutputStream(expected.coerceAtMost(MAX_INITIAL_BUFFER))
        val result = runBlocking {
            http.stream(
                request = request,
                start = segment.fromInclusive,
                end = segment.toInclusive,
            ) { buffer, count -> out.write(buffer, 0, count) }
        }
        return when (result) {
            is HttpNetworkClient.StreamResult.Success -> {
                val bytes = out.toByteArray()
                // A short body would corrupt the piece; a 206 that under-delivers is treated as a
                // transport failure so the caller can try another seed rather than write bad bytes.
                if (bytes.size != expected) {
                    throw IOException("seed returned ${bytes.size} of $expected bytes for $segment")
                }
                bytes
            }

            is HttpNetworkClient.StreamResult.RangeIgnored ->
                throw IOException("seed ignored the byte range for $segment")

            is HttpNetworkClient.StreamResult.Truncated ->
                throw IOException("seed truncated $segment: ${result.received} of ${result.expected} bytes")

            is HttpNetworkClient.StreamResult.Failed ->
                throw IOException("seed request failed: ${result.error}")

            else -> throw IOException("unexpected stream result for $segment")
        }
    }

    companion object {
        /** Cap the pre-allocated buffer so a huge last piece does not over-reserve before the first read. */
        private const val MAX_INITIAL_BUFFER = 8 * 1024 * 1024

        /**
         * Builds the URL of one torrent file under a seed's base URL. Exposed for unit testing of the
         * path-joining and percent-encoding rules, which are the only non-trivial part of the fetcher.
         */
        fun buildFileUrl(torrent: Torrent, seedUrl: String, filePath: List<String>): String {
            val segments = if (torrent.files.size > 1) {
                // Multi-file: the seed root holds a folder named after the torrent, then the file's path.
                buildList { add(torrent.name); addAll(filePath) }
            } else {
                filePath
            }
            val encoded = segments.joinToString("/") { encode(it) }
            return seedUrl.trimEnd('/') + "/" + encoded
        }

        private fun encode(element: String): String =
            URLEncoder.encode(element, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
