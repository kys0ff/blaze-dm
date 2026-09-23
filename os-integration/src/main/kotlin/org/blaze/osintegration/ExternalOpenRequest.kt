package org.blaze.osintegration

import java.nio.file.Path

/**
 * One resource the operating system (browser, file manager, Launch Services, xdg) asked the application
 * to open, already translated out of raw process arguments.
 *
 * This is the single currency between the desktop launcher and the rest of the app: the entry point, the
 * macOS native-event bridge and the single-instance IPC channel all *produce* requests, and only the GUI
 * download workflow (or, headless, the [org.blaze.download.api.DownloadsApi]) consumes them. Nothing
 * downstream ever sees a raw command line.
 *
 * Extensibility: a future file type or deep-link scheme becomes one more [ExternalOpenRequest] variant
 * plus one more branch in the parser/classifier - the inbox, IPC wire format and GUI plumbing stay
 * generic because they only move opaque requests around.
 */
sealed interface ExternalOpenRequest {

    /** A local `.torrent` file that already exists on disk, validated by the parser. */
    data class TorrentFile(val path: Path) : ExternalOpenRequest

    /** A `magnet:?xt=urn:btih:...` URI, kept verbatim exactly as the OS delivered it. */
    data class MagnetUri(val uri: String) : ExternalOpenRequest
}

/**
 * The string form the existing GUI add-download workflow understands: the same text a user would paste
 * into the "Add download" dialog (an absolute file path or the full magnet URI), so external opens
 * reuse the normal dialog, validation and queueing instead of a parallel import path.
 */
val ExternalOpenRequest.displaySource: String
    get() = when (this) {
        is ExternalOpenRequest.TorrentFile -> path.toAbsolutePath().normalize().toString()
        is ExternalOpenRequest.MagnetUri -> uri
    }
