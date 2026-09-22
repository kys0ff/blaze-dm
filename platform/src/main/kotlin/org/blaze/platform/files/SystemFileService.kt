package org.blaze.platform.files

import java.nio.file.Path

/**
 * Desktop file integrations offered from a download's context menu: opening a finished
 * file with the OS default handler, revealing it inside the file manager, and opening a
 * source link in the default browser. Each desktop platform drives a different native
 * mechanism (java.awt.Desktop, `xdg-open`/file-server D-Bus, `open`, `explorer`); the
 * per-OS implementations translate these calls into it and report which capabilities the
 * host actually offers so the UI can hide what wouldn't work.
 *
 * Every operation returns a [Result] instead of throwing so callers can surface a friendly
 * notification without owning the try/catch.
 */
interface SystemFileService {
    /** Whether the host can open a file/folder with its associated application. */
    val canOpenFiles: Boolean

    /** Whether the host can reveal a file inside the system file manager. */
    val canRevealInFolder: Boolean

    /** Whether the host can open an http(s) link in the default browser. */
    val canBrowseLinks: Boolean

    /** Opens [path] (a file launches its default app; a directory opens in the file manager). */
    fun openPath(path: Path): Result<Unit>

    /** Reveals [path] in the file manager, selecting it when it is a file. */
    fun revealInFolder(path: Path): Result<Unit>

    /** Opens [url] in the user's default web browser. */
    fun openInBrowser(url: String): Result<Unit>
}
