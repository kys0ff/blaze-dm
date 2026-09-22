package org.blaze.platform.clipboard

/**
 * Copies text to the system clipboard. Kept behind an interface so the download actions stay
 * platform-agnostic and testable, with the AWT-backed implementation used on every desktop.
 */
interface SystemClipboard {
    /** Whether this host offers a clipboard we can write to. */
    val isSupported: Boolean

    /** Places [text] on the system clipboard, returning a failure when the host has none. */
    fun copy(text: String): Result<Unit>
}
