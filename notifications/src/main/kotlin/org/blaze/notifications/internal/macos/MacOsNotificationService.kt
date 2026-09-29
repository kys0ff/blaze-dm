package org.blaze.notifications.internal.macos

import org.blaze.notifications.api.NotificationService
import org.blaze.notifications.api.NotificationSpec
import org.blaze.notifications.internal.runCli
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * macOS backend built on the system `osascript` bridge:
 * `display notification "…" with title "…"`. It is the only notification channel that
 * needs no bundling or code signing - plain JVM apps get working banners with it.
 *
 * The command is assembled as argv and spawned directly (no shell in between), so the
 * only escaping needed is AppleScript's own: backslash and double quote inside the
 * string literals, and newlines - which cannot sit inside an AppleScript string -
 * are rewritten to the `return` escape (see [appleScriptString]).
 *
 * `display notification` has no urgency concept, so [NotificationSpec]'s severity is
 * dropped here; every banner shows alike. Support is assumed on any macOS host (the
 * binary ships with the OS) and re-probed per call, since Notification Center privacy
 * settings can silently swallow the banner regardless of what this returns.
 */
class MacOsNotificationService : NotificationService {

    private val logger = LoggerFactory.getLogger(MacOsNotificationService::class.java)

    override val backendId: String = "osascript"
    override val isSupported: Boolean = true

    override suspend fun show(notification: NotificationSpec) {
        val command = buildCommand(notification)
        withContext(Dispatchers.IO) {
            runCli(logger, "Failed to post a macOS notification", command)
        }
    }

    override fun dispose() = Unit

    internal fun buildCommand(notification: NotificationSpec): List<String> = listOf(
        "/usr/bin/osascript", "-e", scriptLine(notification),
    )

    companion object {
        /** The single `-e` AppleScript statement for [notification]; pure for tests. */
        fun scriptLine(notification: NotificationSpec): String = buildString {
            // A zero-length string argument would parse as a unary operator; a space is
            // the invisible stand-in AppleScript accepts for "no body".
            append("display notification ")
            append(appleScriptString(notification.body.ifEmpty { " " }))
            append(" with title ")
            append(appleScriptString(notification.title.ifEmpty { " " }))
        }

        /**
         * Quotes [value] as an AppleScript string literal: backslash first, then double
         * quote, then CR/LF folded into the `return` escape AppleScript understands.
         */
        fun appleScriptString(value: String): String {
            val escaped = StringBuilder(value.length)
            for (ch in value) {
                when (ch) {
                    '\\' -> escaped.append("\\\\")
                    '"' -> escaped.append("\\\"")
                    '\r', '\n' -> escaped.append("\\r")
                    else -> escaped.append(ch)
                }
            }
            return "\"$escaped\""
        }
    }
}
