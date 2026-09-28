package org.blaze.osintegration

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Why a single command-line argument could not become an [ExternalOpenRequest]. */
enum class RejectionReason {
    /** An option/flag we do not understand (e.g. macOS `-psn_...`, a JVM flag from a dev run). */
    IGNORED_OPTION,

    /** A URI whose scheme this app is not a handler for (http:, ftp:, ed2k:, a bogus scheme...). */
    UNSUPPORTED_SCHEME,

    /** A `magnet:` string that is not a structurally valid magnet URI. */
    MALFORMED_MAGNET,

    /** A `magnet:` URI without a usable `xt=urn:btih:` identity hash. */
    MISSING_BTIH,

    /** A plain argument that is neither a magnet URI nor a `.torrent` path. */
    NOT_A_TORRENT_FILE,

    /** A `.torrent` path whose file does not exist. */
    FILE_NOT_FOUND,

    /** A `.torrent` path that exists but is a directory or is not readable. */
    FILE_NOT_READABLE,

    /** An argument exceeding the sanity limits for a magnet URI or a file path. */
    TOO_LONG,

    /** An argument carrying control characters (a strong sign of injection or corruption). */
    INVALID_CHARACTERS,
}

/** One rejected raw argument plus the reason, so the launcher can log it without losing detail. */
data class RejectedArgument(val raw: String, val reason: RejectionReason)

/** The outcome of translating a raw process-argument array. */
data class ExternalOpenParseResult(
    val requests: List<ExternalOpenRequest>,
    val rejected: List<RejectedArgument>,
    /** Set when the launcher was invoked with `--new-instance` (dev escape hatch: skip single-instance). */
    val allowMultipleInstances: Boolean,
)

/** How the file system sees a candidate torrent path. */
enum class PathProbe { REGULAR_FILE, DIRECTORY, MISSING, NOT_PERMITTED }

/**
 * Turns raw OS/browser arguments into validated [ExternalOpenRequest]s.
 *
 * Everything passed to a desktop handler is *untrusted input*: this parser never executes, never
 * shells out, and never interprets file *contents* - it only classifies strings. Magnet URIs are kept
 * byte-for-byte (all parameters preserved, no re-encoding); torrent paths are probed for existence and
 * readability through an injectable [probe] so tests can drive every branch without a real disk.
 *
 * Adding a future file type or scheme = one more branch in [classify] + one more
 * [ExternalOpenRequest] variant; callers never change.
 */
class ExternalOpenParser(
    private val probe: (Path) -> PathProbe = ::probePath,
) {

    /** Parses a full process-argument array (any number of entries, in order). */
    fun parse(args: List<String>): ExternalOpenParseResult {
        val requests = mutableListOf<ExternalOpenRequest>()
        val rejected = mutableListOf<RejectedArgument>()
        var allowMultiple = false
        for (raw in args) {
            when {
                raw == OPTION_NEW_INSTANCE -> allowMultiple = true
                // Cleanup is handled by the launcher before anything is parsed (it never opens a GUI);
                // knowing the name here keeps it out of the "unsupported launch argument" log.
                raw == OPTION_CLEANUP -> Unit
                raw.isBlank() -> rejected += RejectedArgument(raw, RejectionReason.MALFORMED_MAGNET)
                else -> classify(raw).fold(
                    onSuccess = { it?.let(requests::add) },
                    onFailure = { e -> rejected += RejectedArgument(raw, reasonOf(e)) },
                )
            }
        }
        return ExternalOpenParseResult(requests, rejected, allowMultiple)
    }

    /**
     * Classifies a single raw argument (a command-line entry, an argv from the macOS launcher, one
     * value from the IPC channel, or a dropped/pasted string) into a request. Returns null for
     * "accepted but nothing to do" results; fails with a [RejectionException] carrying the reason for
     * anything unusable. [allowWebUrls] widens the accepted vocabulary with plain `http(s)` links -
     * only in-app surfaces (window drops, clipboard monitoring) set it; the OS launch path never does,
     * so a stray `http:` argv stays the "not a handler for this scheme" rejection it always was.
     * [allowArtifactPaths] widens further to existing local *folders* and files: link-list text
     * files become [ExternalOpenRequest.LinkListFile] and anything else that exists becomes a
     * [ExternalOpenRequest.PartialDownload] candidate. Only the explicit, goal-directed window-drop
     * gesture sets it - a stray copied file path on the clipboard must not queue an import attempt.
     */
    fun classify(
        raw: String,
        allowWebUrls: Boolean = false,
        allowArtifactPaths: Boolean = false,
    ): Result<ExternalOpenRequest?> = runCatching {
        val value = raw.trim()
        when {
            value.isEmpty() -> null
            value.startsWith("-") -> throw Rejection(RejectionReason.IGNORED_OPTION)
            value.hasScheme() -> classifyUri(value, allowWebUrls, allowArtifactPaths)
            else -> classifyPath(value, allowArtifactPaths)
        }
    }

    /**
     * Classifies one value handed over by the window drop surface: magnets, web links, local torrent
     * files, link-list text files and candidate partial downloads (files/folders that exist).
     * Same guarantees as [classify].
     */
    fun classifyForDrop(raw: String): Result<ExternalOpenRequest?> =
        classify(raw, allowWebUrls = true, allowArtifactPaths = true)

    /**
     * Classifies one value observed by the clipboard monitor: an implicit, non-goal-directed
     * surface, so it takes links and magnets but never treats an arbitrary existing path as an
     * import candidate (a copied file path in a file manager is not a download request).
     */
    fun classifyForClipboard(raw: String): Result<ExternalOpenRequest?> =
        classify(raw, allowWebUrls = true)

    private fun classifyUri(
        value: String,
        allowWebUrls: Boolean,
        allowArtifactPaths: Boolean,
    ): ExternalOpenRequest? {
        val scheme = value.substringBefore(':').lowercase()
        return when (scheme) {
            "magnet" -> classifyMagnet(value)
            "http", "https" -> if (allowWebUrls) classifyWebUrl(value) else throw Rejection(RejectionReason.UNSUPPORTED_SCHEME)
            // Browsers sometimes hand over file:// URIs for a downloaded .torrent; accept only when it
            // decodes to a real local artifact, otherwise treat it like any unsupported scheme.
            "file" -> {
                val path = runCatching { Paths.get(URI(value)) }.getOrNull()
                    ?: throw Rejection(RejectionReason.MALFORMED_MAGNET)
                classifyPath(path.toAbsolutePath().toString(), allowArtifactPaths)
            }
            else -> throw Rejection(RejectionReason.UNSUPPORTED_SCHEME)
        }
    }

    /**
     * Sanity-checks a dropped/pasted web link without interpreting it: no control characters, a
     * plausible length ceiling, and a non-empty authority after `scheme://`. The URL is kept
     * verbatim (query strings, fragments, encodings) so the engine sees exactly what the user dropped.
     */
    private fun classifyWebUrl(value: String): ExternalOpenRequest {
        if (value.length > MAX_URL_LENGTH) throw Rejection(RejectionReason.TOO_LONG)
        if (value.any { it.code < 0x20 || it == '\u007f' }) throw Rejection(RejectionReason.INVALID_CHARACTERS)
        val rest = value.substringAfter("://", "")
        if (rest.isBlank() || rest[0] == '/') throw Rejection(RejectionReason.UNSUPPORTED_SCHEME)
        return ExternalOpenRequest.WebLink(value)
    }

    private fun classifyMagnet(value: String): ExternalOpenRequest {
        if (value.length > MAX_MAGNET_URI_LENGTH) throw Rejection(RejectionReason.TOO_LONG)
        if (value.any { it.code < 0x20 || it == '\u007f' }) throw Rejection(RejectionReason.INVALID_CHARACTERS)
        if (!value.startsWith("magnet:", ignoreCase = true)) throw Rejection(RejectionReason.UNSUPPORTED_SCHEME)
        // Parsed as text rather than via java.net.URI: browsers hand over percent-encoded URIs, but
        // lossy desktop launchers can deliver a `dn=` with a raw space, which URI would reject outright.
        val query = value.substringAfter(':', "")
            .removePrefix("?")
            .takeIf { it.isNotBlank() }
            ?: throw Rejection(RejectionReason.MISSING_BTIH)
        // Only the presence/shape of xt=urn:btih: is verified; the URI itself is kept verbatim so
        // every other parameter (dn, tr, ws, unknown future keys) survives untouched.
        val xt = query.split('&')
            .firstOrNull { it.startsWith("xt=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let { percentDecode(it) ?: it }
            ?: throw Rejection(RejectionReason.MISSING_BTIH)
        if (!xt.startsWith("urn:btih:", ignoreCase = true)) throw Rejection(RejectionReason.MISSING_BTIH)
        val hash = xt.substringAfterLast(':')
        val isHex40 = hash.length == 40 && hash.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        val isBase32 = hash.length == 32 && hash.all { it.uppercaseChar() in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" }
        if (!isHex40 && !isBase32) throw Rejection(RejectionReason.MISSING_BTIH)
        // Reject structurally broken percent-encoding anywhere in the URI.
        if (percentDecode(value) == null) throw Rejection(RejectionReason.MALFORMED_MAGNET)
        return ExternalOpenRequest.MagnetUri(value)
    }

    private fun classifyPath(value: String, allowArtifactPaths: Boolean): ExternalOpenRequest? {
        if (value.length > MAX_PATH_LENGTH) throw Rejection(RejectionReason.TOO_LONG)
        if (value.any { it.code < 0x20 || it == '\u007f' }) throw Rejection(RejectionReason.INVALID_CHARACTERS)
        val isTorrent = value.endsWith(".torrent", ignoreCase = true)
        if (!isTorrent && !allowArtifactPaths) throw Rejection(RejectionReason.NOT_A_TORRENT_FILE)
        val path = runCatching { Paths.get(value) }
            .getOrElse { throw Rejection(RejectionReason.NOT_A_TORRENT_FILE) }
            .toAbsolutePath().normalize()
        return when (probe(path)) {
            PathProbe.REGULAR_FILE -> when {
                isTorrent -> ExternalOpenRequest.TorrentFile(path)
                isLinkListFile(path) -> ExternalOpenRequest.LinkListFile(path)
                else -> ExternalOpenRequest.PartialDownload(path)
            }
            // A folder only ever means something on the drop surface: a candidate torrent folder
            // whose portable marker the engine will look for. On argv it stays the classic error.
            PathProbe.DIRECTORY ->
                if (allowArtifactPaths) ExternalOpenRequest.PartialDownload(path)
                else throw Rejection(RejectionReason.FILE_NOT_READABLE)
            PathProbe.MISSING -> throw Rejection(RejectionReason.FILE_NOT_FOUND)
            PathProbe.NOT_PERMITTED -> throw Rejection(RejectionReason.FILE_NOT_READABLE)
        }
    }

    /** Decodes percent escapes; null when an escape is malformed. Never throws. */
    private fun percentDecode(value: String): String? = runCatching {
        java.net.URLDecoder.decode(value, Charsets.UTF_8)
    }.getOrNull()

    private fun reasonOf(e: Throwable): RejectionReason =
        (e as? Rejection)?.reason ?: RejectionReason.MALFORMED_MAGNET

    /** Rejects Windows drive letters ("C:\...") looking like a scheme, accepts everything else URI-ish. */
    private fun String.hasScheme(): Boolean {
        val colon = indexOf(':')
        if (colon <= 1) return false // no scheme, or a single-letter drive prefix
        val scheme = substring(0, colon)
        return scheme.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*"))
    }

    private class Rejection(val reason: RejectionReason) : Exception(reason.name)

    companion object {
        const val OPTION_NEW_INSTANCE: String = "--new-instance"

        /**
         * Removes the desktop registrations Blaze published at the user level and exits. Named here so
         * the launcher's flag vocabulary lives in one place; the GUI acts on it before parsing.
         */
        const val OPTION_CLEANUP: String = "--cleanup"

        /** Generous ceiling: browsers cap URLs around 2k, but tracker-heavy magnets can legitimately grow. */
        const val MAX_MAGNET_URI_LENGTH: Int = 8 * 1024

        /** Beyond this a "path" is an attack or corruption, not a file the user clicked. */
        const val MAX_PATH_LENGTH: Int = 4 * 1024

        /** Ceiling for a dropped web link; signed CDN URLs grow long, but nothing legitimate reaches 8k. */
        const val MAX_URL_LENGTH: Int = 8 * 1024

        /**
         * Extensions the drop surface treats as link lists (one URL/magnet per line). Kept in step
         * with what the batch add-download dialog knows how to load; plain `txt` is the contract,
         * the others are the conventional names for the same "one link per line" format.
         */
        val LINK_LIST_EXTENSIONS: Set<String> = setOf("txt", "list", "links", "urls")

        /** True when [path]'s extension marks it as a link-list text file (case-insensitive). */
        fun isLinkListFile(path: Path): Boolean =
            path.fileName.toString().substringAfterLast('.', "").lowercase() in LINK_LIST_EXTENSIONS
    }
}

/** Real file-system probe used outside tests. */
internal fun probePath(path: Path): PathProbe = when {
    !Files.exists(path) -> PathProbe.MISSING
    Files.isDirectory(path) -> PathProbe.DIRECTORY
    !Files.isRegularFile(path) -> PathProbe.NOT_PERMITTED // socket, fifo, device: never a torrent
    !Files.isReadable(path) -> PathProbe.NOT_PERMITTED
    else -> PathProbe.REGULAR_FILE
}

/**
 * Convenience for native event bridges (macOS hands over `File`s/`URI`s, not argv): classify one value
 * and drop it into the inbox when usable. Rejections are surfaced to the caller's logger.
 */
fun ExternalOpenParser.classifyOrNull(raw: String): ExternalOpenRequest? =
    classify(raw).getOrNull()

/** Same convenience for the window-drop surface: links, magnets, torrents, link lists, partials. */
fun ExternalOpenParser.classifyForDropOrNull(raw: String): ExternalOpenRequest? =
    classifyForDrop(raw).getOrNull()

/** The clipboard monitor's narrower vocabulary: links and magnets, never arbitrary path artifacts. */
fun ExternalOpenParser.classifyForClipboardOrNull(raw: String): ExternalOpenRequest? =
    classifyForClipboard(raw).getOrNull()
