package org.blaze.engine.archive

import org.blaze.engine.api.DownloadRequest
import org.blaze.engine.network.HttpNetworkClient
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/**
 * A [RemoteByteSource] backed by the engine's shared [HttpNetworkClient], so remote ZIP inspection and
 * selective extraction ride the exact same ranged-read, redirect and header machinery an ordinary HTTP
 * download uses — no parallel networking stack.
 *
 * The first call probes the resource once to learn its length and whether ranged reads are honoured;
 * that single probe feeds both the "can we do this selectively at all?" decision and every windowed
 * read that follows, and the resolved request keeps carrying the original headers (a signed URL's query,
 * a required `Referer`) so authentication survives redirects.
 */
class HttpRemoteByteSource(
    private val client: HttpNetworkClient,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : RemoteByteSource {

    @Volatile
    private var probed: HttpNetworkClient.Probe? = null

    private val request = DownloadRequest.Http(
        name = url.substringAfterLast('/').substringBefore('?').ifBlank { "archive" },
        url = url,
        destination = Path.of(url.substringAfterLast('/').ifBlank { "archive" }),
        headers = headers,
    )

    private suspend fun ensureProbe(): HttpNetworkClient.Probe {
        probed?.let { return it }
        val result = client.probe(request)
        probed = result
        return result
    }

    override suspend fun size(): Long? {
        val probe = ensureProbe()
        return probe.totalBytes.takeIf { it > 0 }
    }

    override val rangeSupported: Boolean
        get() = probed?.acceptsRanges == true

    override suspend fun read(start: Long, length: Long): ByteArray {
        ensureProbe()
        val out = ByteArrayOutputStream(minOf(length, 8L * 1024L * 1024L).toInt().coerceAtLeast(1024))
        val result = client.stream(
            request,
            start = start,
            end = if (length > 0) start + length - 1 else start
        ) { buffer, count ->
            out.write(buffer, 0, count)
        }
        return when (result) {
            is HttpNetworkClient.StreamResult.Success -> out.toByteArray()
            is HttpNetworkClient.StreamResult.RangeIgnored ->
                throw ArchiveAccessException("server ignored the byte range; selective retrieval is unavailable")

            is HttpNetworkClient.StreamResult.Truncated ->
                throw ArchiveAccessException("range $start+$length returned only ${result.received} of $length bytes")

            is HttpNetworkClient.StreamResult.Failed ->
                throw ArchiveAccessException("range read failed: ${result.error}")

            else -> throw ArchiveAccessException("unexpected stream result reading range $start+$length")
        }
    }
}
