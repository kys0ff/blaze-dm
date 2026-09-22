package org.blaze.platform.clipboard

/**
 * Reads and writes plain text on the system clipboard. Kept behind an interface so the
 * download actions stay platform-agnostic and testable, with the AWT-backed implementation
 * used on every desktop.
 */
interface SystemClipboard {
    /** Whether this host offers a clipboard we can read from and write to. */
    val isSupported: Boolean

    /** Places [text] on the system clipboard, returning a failure when the host has none. */
    fun copy(text: String): Result<Unit>

    /**
     * Returns the current plain-text clipboard contents, or null when the host has no
     * clipboard or its contents are not text.
     */
    fun paste(): String?
}
