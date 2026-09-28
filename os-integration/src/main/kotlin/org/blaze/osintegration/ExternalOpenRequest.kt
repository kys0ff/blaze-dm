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

    /**
     * A plain `http(s)` web link. Only produced by in-app surfaces that legitimately receive links
     * (drag-and-drop onto the window, clipboard monitoring); argv and file associations never hand
     * over a bare URL, so the OS launch path keeps rejecting unknown schemes.
     */
    data class WebLink(val url: String) : ExternalOpenRequest

    /**
     * An existing local text file holding one link/magnet per line (a "link list"), produced only
     * by the window-drop vocabulary. The consumer opens it through the batch add-download flow;
     * the file's *contents* are never interpreted by the parser itself.
     */
    data class LinkListFile(val path: Path) : ExternalOpenRequest

    /**
     * A file or folder dropped onto the window that is a *candidate* portable (unfinished)
     * download. Detection stays content-based downstream - the classifier only promises the path
     * exists; whether it carries an embedded Blaze resume manifest is decided by the engine.
     */
    data class PartialDownload(val path: Path) : ExternalOpenRequest
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
        is ExternalOpenRequest.WebLink -> url
        is ExternalOpenRequest.LinkListFile -> path.toAbsolutePath().normalize().toString()
        is ExternalOpenRequest.PartialDownload -> path.toAbsolutePath().normalize().toString()
    }
