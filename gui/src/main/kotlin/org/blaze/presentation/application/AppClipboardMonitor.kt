package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import org.blaze.data.AppSettingsRepository
import org.blaze.osintegration.ExternalOpenInbox
import org.blaze.osintegration.ExternalOpenParser
import org.blaze.osintegration.classifyForDropOrNull
import org.blaze.platform.clipboard.SystemClipboard
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** How often the clipboard is sampled. AWT exposes no portable flush listener for clipboards
 *  we do not own, so a cheap poll (a single `getData` on a cached flavor) is the pragmatic
 *  standard - the same approach every mainstream download manager uses. */
private val CLIPBOARD_POLL_INTERVAL: Duration = 1_500.milliseconds

/**
 * Hosts the optional clipboard monitor: while `clipboardMonitoringEnabled` is on, it watches the
 * system clipboard for *changes* and classifies each newly copied value with the shared
 * [ExternalOpenParser] - the same validation boundary as argv, IPC and window drops, widened
 * with plain web links. Recognised links/magnets/`.torrent` paths are submitted to the
 * [ExternalOpenInbox], so a clipboard hit reuses the exact same user-visible flow as a browser
 * handler: the add-download dialog opens pre-filled, and nothing is queued until the user confirms.
 *
 * Deliberate behaviours:
 * - the value already on the clipboard when monitoring starts is only remembered, never offered
 *   (monitoring must not resurrect a copy from before the user enabled it);
 * - non-link text is simply remembered as "seen", so it never re-triggers;
 * - the inbox de-duplicates identical queued requests, so a lingering copy cannot stack dialogs.
 */
@Composable
fun AppClipboardMonitor() {
    val appSettingsRepository = koinInject<AppSettingsRepository>()
    val clipboard = koinInject<SystemClipboard>()
    val inbox = koinInject<ExternalOpenInbox>()
    val parser = koinInject<ExternalOpenParser>()

    val appSettings by appSettingsRepository.settings.collectAsState()
    val enabled = appSettings.clipboardMonitoringEnabled

    LaunchedEffect(enabled, clipboard.isSupported) {
        if (!enabled || !clipboard.isSupported) return@LaunchedEffect
        // Seed with the current content so the first poll can only ever observe a real change.
        var lastSeen: String? = runCatching { clipboard.paste()?.trim() }.getOrNull()
        while (true) {
            delay(CLIPBOARD_POLL_INTERVAL)
            val text = runCatching { clipboard.paste()?.trim() }.getOrNull()
            if (text != null && text != lastSeen) {
                lastSeen = text
                parser.classifyForDropOrNull(text)?.let(inbox::submit)
            }
        }
    }
}
