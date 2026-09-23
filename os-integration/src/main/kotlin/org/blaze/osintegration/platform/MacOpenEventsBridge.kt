package org.blaze.osintegration.platform

import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.OpenFilesHandler
import java.awt.desktop.OpenURIHandler
import java.io.File

/**
 * Bridges macOS's *application already running* lifecycle events into the shared inbox.
 *
 * Launch Services does not re-exec the app with argv when it is alive: Finder/a browser delivers an
 * `odoc`/`GURL` Apple event instead. The JDK's AWT desktop API surfaces those as open-files/open-URI
 * handler callbacks, so `main(args)` alone can never see a warm open - this bridge is the required
 * second door, and it is registered before the first window exists so even events that arrive
 * mid-launch are queued by AWT and handed over normally (cold start included, where macOS also sends
 * `oapp`+`odoc` rather than argv).
 *
 * Every delivered path/URI passes through the same [ExternalOpenParser] as command-line argv: this
 * bridge adds no trust of its own. Returns false on platforms without the AWT desktop event API
 * (Linux/Windows are no-ops there; their events never fire anyway).
 */
object MacOpenEventsBridge {
    private val logger = LoggerFactory.getLogger(MacOpenEventsBridge::class.java)

    /**
     * Registers the handlers. [onReopen] lets the app un-hide itself when the Dock icon is clicked
     * while it is running (the classic macOS "reactivate" gesture).
     */
    fun register(inbox: ExternalOpenInbox, parser: ExternalOpenParser, onReopen: () -> Unit = {}): Boolean {
        if (!Desktop.isDesktopSupported()) return false
        val desktop = Desktop.getDesktop()
        var registered = false
        runCatching {
            if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
                desktop.setOpenFileHandler(OpenFilesHandler { event ->
                    submitAll(inbox, parser, event.files.orEmpty().map(File::getAbsolutePath))
                })
                registered = true
            }
        }.onFailure { logger.warn("Could not register the macOS open-files handler", it) }
        runCatching {
            if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
                desktop.setOpenURIHandler(OpenURIHandler { event ->
                    event.uri?.toString()?.let { submitAll(inbox, parser, listOf(it)) }
                })
                registered = true
            }
        }.onFailure { logger.warn("Could not register the macOS open-URI handler", it) }
        runCatching {
            if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                desktop.addAppEventListener(AppReopenedListener { onReopen() })
            }
        }.onFailure { logger.warn("Could not register the macOS reopen handler", it) }
        return registered
    }

    private fun submitAll(inbox: ExternalOpenInbox, parser: ExternalOpenParser, raws: List<String>) {
        for (raw in raws) {
            parser.classify(raw).onSuccess { it?.let(inbox::submit) }
                .onFailure { logger.warn("Ignored a macOS open event: {} ({})", raw.take(120), it.message) }
        }
    }
}
