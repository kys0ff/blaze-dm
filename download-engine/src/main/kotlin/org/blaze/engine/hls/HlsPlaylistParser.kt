package org.blaze.engine.hls

import java.net.URI

/**
 * A defensive, allocation-light parser for HTTP Live Streaming playlists (RFC 8216).
 *
 * It is intentionally strict about what it accepts and honest about what it cannot understand:
 * remote playlist text is untrusted input, so a payload that is not a real `#EXTM3U` playlist raises
 * [HlsPlaylistException] rather than being silently mis-Read as one. Relative `#EXTINF`/variant URIs
 * are resolved against the base URL of the playlist that carried them, which is the only correct
 * resolution point for HLS.
 */
object HlsPlaylistParser {

    private const val MAGIC = "#EXTM3U"

    // Key formats that name a real DRM system. Their presence is a *confirmed* protection signal.
    private val DRM_KEYFORMATS = listOf(
        "com.apple.streamingkeydelivery", // FairPlay
        "com.widevine", // Widevine (and its modular/experimental variants)
        "uuid",
    )

    /**
     * Parse [text] as an HLS playlist fetched from [baseUrl].
     *
     * @throws HlsPlaylistException when [text] is not a playlist, or is empty/truncated to the point
     *   of carrying no playable structure at all.
     */
    fun parse(text: String, baseUrl: String): HlsPlaylist {
        val lines = text.split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }

        if (lines.none { it.startsWith(MAGIC) }) {
            throw HlsPlaylistException("Not an HLS playlist: missing #EXTM3U header")
        }

        var isMaster = false
        var mediaSequence = 0L
        var targetDuration: Double? = null
        var endList = false

        // The key in force for the *next* segment tag, tracked as #EXT-X-KEY lines are encountered.
        var currentMethod = HlsKeyMethod.None
        var currentKeyUri: String? = null
        var currentIv: String? = null

        val segments = ArrayList<HlsSegment>()
        val drmIndicators = ArrayList<String>()
        var sawSessionKey = false

        var pendingExtinfDuration: Double? = null
        var pendingDiscontinuity = false
        var lastWasStreamInf = false

        for (raw in lines) {
            val line = raw.trim()
            if (line == MAGIC || line.startsWith("#EXT-X-VERSION")) continue

            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    isMaster = true
                    lastWasStreamInf = true
                }

                line.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    mediaSequence = line.afterColon().toLongOrNull() ?: mediaSequence

                line.startsWith("#EXT-X-TARGETDURATION") ->
                    targetDuration = line.afterColon().toDoubleOrNull() ?: targetDuration

                line.startsWith("#EXT-X-ENDLIST") -> endList = true

                line.startsWith("#EXT-X-DISCONTINUITY") -> {
                    pendingDiscontinuity = true
                }

                line.startsWith("#EXT-X-SESSION-KEY") -> {
                    sawSessionKey = true
                    classifyKeyAttributes(line.afterColon(), drmIndicators, base = baseUrl)
                }

                line.startsWith("#EXT-X-KEY") -> {
                    val attrs = parseAttributeList(line.afterColon())
                    currentMethod = keyMethodOf(attrs["METHOD"])
                    currentKeyUri = attrs["URI"]?.let { resolveUrl(baseUrl, it) }
                    currentIv = attrs["IV"]?.removePrefix("0x")?.removePrefix("0X")
                    if (currentMethod == HlsKeyMethod.None) {
                        currentKeyUri = null
                        currentIv = null
                    }
                    if (currentMethod == HlsKeyMethod.SampleAes) {
                        drmIndicators += "#EXT-X-KEY METHOD=SAMPLE-AES"
                    }
                }

                line.startsWith("#EXTINF") -> {
                    // "#EXTINF:9.008,title" — the duration is the field before the first comma.
                    val value = line.afterColon()
                    pendingExtinfDuration = value.substringBefore(',').trim().toDoubleOrNull()
                }

                line.startsWith("#") -> {
                    // Any other comment/tag line is not a resource URI; leave pending state as is.
                }

                else -> {
                    // A bare line is a URI: a variant target right after #EXT-X-STREAM-INF, else a segment.
                    val absolute = resolveUrl(baseUrl, line)
                    if (lastWasStreamInf) {
                        lastWasStreamInf = false
                    } else if (pendingExtinfDuration != null) {
                        segments += HlsSegment(
                            uri = absolute,
                            durationSeconds = pendingExtinfDuration,
                            discontinuity = pendingDiscontinuity,
                            keyUri = if (currentMethod == HlsKeyMethod.Aes128) currentKeyUri else null,
                            iv = if (currentMethod == HlsKeyMethod.Aes128) currentIv else null,
                        )
                        pendingExtinfDuration = null
                        pendingDiscontinuity = false
                    }
                }
            }
        }

        val protection = classifyProtection(currentMethod, drmIndicators, sawSessionKey, segments)
        val kind = if (isMaster) HlsPlaylistKind.Master else HlsPlaylistKind.Media
        val masterVariants = if (isMaster) collectVariants(lines, baseUrl) else emptyList()

        // A master is only usable once it names at least one variant; a media playlist only once it
        // names at least one segment. Anything else is a malformed or truncated body that we refuse
        // rather than pretend to download.
        val playable = when (kind) {
            HlsPlaylistKind.Master -> masterVariants.isNotEmpty()
            HlsPlaylistKind.Media -> segments.isNotEmpty()
        }
        if (!playable) {
            throw HlsPlaylistException(
                if (kind == HlsPlaylistKind.Master) "HLS master playlist declares no usable variant"
                else "HLS media playlist carries no segments"
            )
        }

        return HlsPlaylist(
            kind = kind,
            segments = segments,
            variants = masterVariants,
            targetDurationSeconds = targetDuration,
            mediaSequence = mediaSequence,
            endList = endList,
            keyMethod = currentMethod,
            keyUri = currentKeyUri,
            protection = protection,
            drmIndicators = drmIndicators.toList(),
        )
    }

    /**
     * Second, variant-focused pass over a master playlist's lines. Kept separate from the segment loop
     * because a variant's URI is only known on the line *after* its `#EXT-X-STREAM-INF`, and pairing
     * them cleanly is clearer than threading two pieces of state through one loop.
     */
    private fun collectVariants(lines: List<String>, baseUrl: String): List<HlsVariant> {
        val out = ArrayList<HlsVariant>()
        var pending: Map<String, String>? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pending = parseAttributeList(line.afterColon())
                continue
            }
            if (line.startsWith("#")) continue
            val attrs = pending ?: continue
            pending = null
            out += HlsVariant(
                index = out.size,
                uri = resolveUrl(baseUrl, line),
                bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
                resolution = attrs["RESOLUTION"],
                codecs = attrs["CODECS"],
                language = attrs["LANGUAGE"],
                name = attrs["NAME"],
            )
        }
        return out
    }

    /** True when [text] begins like an HLS playlist, used as a cheap pre-flight before parsing. */
    fun looksLikePlaylist(text: String): Boolean = text.trimStart().startsWith(MAGIC)

    private fun classifyKeyAttributes(attrs: String, indicators: MutableList<String>, base: String) {
        val map = parseAttributeList(attrs)
        val method = keyMethodOf(map["METHOD"])
        val format = map["KEYFORMAT"]?.lowercase() ?: ""
        if (method == HlsKeyMethod.SampleAes) {
            indicators += "#EXT-X-SESSION-KEY METHOD=SAMPLE-AES"
        } else if (DRM_KEYFORMATS.any { format.contains(it) }) {
            indicators += "#EXT-X-SESSION-KEY KEYFORMAT=${map["KEYFORMAT"]}"
        } else if (method == HlsKeyMethod.Unknown) {
            indicators += "#EXT-X-SESSION-KEY METHOD=${map["METHOD"]}"
        }
    }

    private fun classifyProtection(
        playlistMethod: HlsKeyMethod,
        indicators: List<String>,
        sawSessionKey: Boolean,
        segments: List<HlsSegment>,
    ): HlsProtection = when {
        indicators.isNotEmpty() -> HlsProtection.DrmSuspected
        playlistMethod == HlsKeyMethod.SampleAes -> HlsProtection.DrmSuspected
        playlistMethod == HlsKeyMethod.Unknown -> HlsProtection.Uncertain
        playlistMethod == HlsKeyMethod.Aes128 || segments.any { it.keyUri != null } ->
            HlsProtection.OrdinaryEncryption

        sawSessionKey -> HlsProtection.Uncertain
        else -> HlsProtection.None
    }

    private fun keyMethodOf(raw: String?): HlsKeyMethod = when (raw?.trim()?.uppercase()) {
        null, "", "NONE" -> HlsKeyMethod.None
        "AES-128" -> HlsKeyMethod.Aes128
        "SAMPLE-AES", "SAMPLE-AES-CENC", "SAMPLE-AES-CTR" -> HlsKeyMethod.SampleAes
        else -> HlsKeyMethod.Unknown
    }

    /** Everything after the first colon, for a `#TAG:value` or `#TAG:KEY=VAL` line. */
    private fun String.afterColon(): String = substringAfter(':', missingDelimiterValue = "").trim()

    /**
     * Parse an HLS attribute list (`BANDWIDTH=1000,URI="a,b",CODECS="avc1.4d400d"`), honouring
     * double-quoted values that legitimately contain commas.
     */
    internal fun parseAttributeList(input: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        val n = input.length
        while (i < n) {
            // Read key up to '='.
            val eq = input.indexOf('=', i)
            if (eq < 0) break
            val key = input.substring(i, eq).trim()
            var j = eq + 1
            val value: String
            if (j < n && input[j] == '"') {
                val close = input.indexOf('"', j + 1)
                if (close < 0) {
                    value = input.substring(j + 1)
                    i = n
                } else {
                    value = input.substring(j + 1, close)
                    i = close + 1
                    // Skip the comma that follows the quoted value.
                    if (i < n && input[i] == ',') i++
                }
            } else {
                val comma = input.indexOf(',', j)
                if (comma < 0) {
                    value = input.substring(j)
                    i = n
                } else {
                    value = input.substring(j, comma)
                    i = comma + 1
                }
            }
            if (key.isNotEmpty()) result[key.uppercase()] = value.trim()
        }
        return result
    }

    /**
     * Resolve [reference] against [base]. HLS references may be relative, protocol-relative or
     * absolute; a URL that cannot be parsed is returned verbatim so the fetch layer produces a clear
     * network error rather than the parser guessing.
     */
    internal fun resolveUrl(base: String, reference: String): String {
        val ref = reference.trim()
        if (ref.isEmpty()) return base
        return runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)
    }
}
