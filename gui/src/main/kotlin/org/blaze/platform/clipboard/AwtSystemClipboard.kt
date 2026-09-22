package org.blaze.platform.clipboard

import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.EventQueue

private val logger = LoggerFactory.getLogger("org.blaze.platform.clipboard")

/**
 * Clipboard backed by the AWT toolkit. The write is handed to the Swing event thread (where the
 * OS clipboard expects ownership transfers to happen) while still surfacing failures to the
 * caller: [EventQueue.invokeAndWait] is used off the EDT, and the action is run inline when we
 * are already on it.
 */
class AwtSystemClipboard : SystemClipboard {

    override val isSupported: Boolean
        get() = runCatching {
            !GraphicsEnvironment.isHeadless() && Toolkit.getDefaultToolkit().systemClipboard != null
        }.getOrDefault(false)

    override fun copy(text: String): Result<Unit> = runCatching {
        if (GraphicsEnvironment.isHeadless()) {
            error("No clipboard available in a headless session")
        }
        runOnEdt {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }.getOrThrow()
    }.onFailure { logger.warn("Failed to copy text to the clipboard", it) }

    private fun runOnEdt(action: () -> Unit): Result<Unit> = runCatching {
        if (EventQueue.isDispatchThread()) {
            action()
        } else {
            EventQueue.invokeAndWait(action)
        }
    }
}
