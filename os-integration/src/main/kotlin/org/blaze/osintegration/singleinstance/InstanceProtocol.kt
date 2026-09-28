package org.blaze.osintegration.singleinstance

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.osintegration.ExternalOpenRequest
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * The wire protocol of the single-instance channel: exactly one JSON frame per connection, one reply
 * line, then close. No sessions, no commands beyond "deliver these requests".
 *
 * The frame carries the shared [InstanceTokenStore] token, so even the local transport is authenticated
 * (an AF_UNIX socket or a loopback socket alike), and it is length-capped before parsing so a hostile
 * local process cannot make the owner allocate unbounded memory. Requests cross the wire as the same
 * strings the parser accepts, and are *re-validated* on arrival: the channel is authenticated, not
 * trusted, because a forwarded payload originates from whatever the browser handed the second process.
 */
internal object InstanceProtocol {

    /** Hard ceiling for one frame; a full-screen wall of trackers still fits far below this. */
    const val MAX_FRAME_BYTES: Long = 4L * 1024 * 1024

    const val REPLY_OK: String = "OK"
    const val REPLY_ERR: String = "ERR"

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    @Serializable
    data class WireOpen(val kind: String, val value: String)

    @Serializable
    data class OpenFrame(val token: String, val requests: List<WireOpen>)

    fun encode(token: String, requests: List<ExternalOpenRequest>): String =
        json.encodeToString(
            OpenFrame.serializer(),
            OpenFrame(
                token = token,
                requests = requests.map {
                    when (it) {
                        is ExternalOpenRequest.TorrentFile -> WireOpen(KIND_TORRENT, it.displaySourceString())
                        is ExternalOpenRequest.MagnetUri -> WireOpen(KIND_MAGNET, it.uri)
                        is ExternalOpenRequest.WebLink -> WireOpen(KIND_WEB, it.url)
                    }
                },
            ),
        )

    /** Decodes a frame; null on any structural problem. Callers must pre-cap the byte length. */
    fun decode(frame: String): OpenFrame? =
        runCatching { json.decodeFromString(OpenFrame.serializer(), frame) }.getOrNull()

    /**
     * Maps wire entries back to requests through the shared parser (re-validation). Anything the
     * parser rejects is dropped; true is returned only when every entry survived, so a partially
     * corrupt frame is visible to the server log.
     */
    fun toRequests(frame: OpenFrame, parser: ExternalOpenParser): List<ExternalOpenRequest>? =
        frame.requests.mapNotNull { wire ->
            when (wire.kind) {
                KIND_TORRENT, KIND_MAGNET -> parser.classify(wire.value).getOrNull()
                // Web links only originate from in-app surfaces (drops / clipboard) on the forwarding
                // side, so the receiving side re-validates them with the same widened vocabulary.
                KIND_WEB -> parser.classify(wire.value, allowWebUrls = true).getOrNull()
                else -> null // unknown future kind: drop, never execute
            }
        }.takeIf { it.size == frame.requests.size }

    fun writeFrame(output: OutputStream, frame: String) {
        // Single UTF-8 line; no shell, no interpolation - the payload is pure data.
        val bytes = frame.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size.toLong() > MAX_FRAME_BYTES) throw IOException("frame exceeds the size cap")
        output.write(bytes)
        output.write('\n'.code)
        output.flush()
    }

    /** Reads one capped line; null when the peer overflows the cap or closes early. */
    fun readLineCapped(input: InputStream): String? {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) return if (buffer.size() == 0) null else decode(buffer)
            if (c == '\n'.code) return decode(buffer)
            if (buffer.size() >= MAX_FRAME_BYTES) return null // cap before any allocation growth
            buffer.write(c)
        }
    }

    /** UTF-8 decode of a complete line; malformed bytes reject the frame, never corrupt it. */
    private fun decode(buffer: java.io.ByteArrayOutputStream): String? {
        var size = buffer.size()
        if (size > 0 && buffer.toByteArray()[size - 1] == '\r'.code.toByte()) size--
        if (size == 0) return ""
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(buffer.toByteArray(), 0, size))
                .toString()
        }.getOrNull()
    }

    private const val KIND_TORRENT = "torrent"
    private const val KIND_MAGNET = "magnet"
    private const val KIND_WEB = "web"

    private fun ExternalOpenRequest.displaySourceString(): String = when (this) {
        is ExternalOpenRequest.TorrentFile -> path.toString()
        is ExternalOpenRequest.MagnetUri -> uri
        is ExternalOpenRequest.WebLink -> url
    }
}
