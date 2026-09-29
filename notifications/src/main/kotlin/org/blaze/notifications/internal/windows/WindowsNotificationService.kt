package org.blaze.notifications.internal.windows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blaze.notifications.api.NotificationService
import org.blaze.notifications.api.NotificationSpec
import org.blaze.notifications.api.NotificationUrgency
import org.blaze.notifications.internal.runCli
import org.blaze.notifications.internal.windows.WindowsNotificationService.Companion.powerShellString
import org.slf4j.LoggerFactory

/**
 * Windows backend built on PowerShell's `NotifyIcon.ShowBalloonTip` - the tray-balloon
 * API every Windows version since Vista surfaces as a toast in the Action Center.
 * Unlike WinRT/`toast-win` it needs no packaging identity, so it works for a plain
 * `java -jar` run just as for an installed app.
 *
 * The helper is spawned as argv (no shell interpolation) and the payload is embedded in
 * PowerShell *single-quoted* strings, where the only special character is the
 * apostrophe (doubled to escape, see [powerShellString]); newlines - balloons render a
 * single line anyway - fold to spaces.
 *
 * [NotificationUrgency.CRITICAL] raises the balloon's severity icon; LOW and NORMAL
 * both land as an informational toast, matching what the API can express. Support is
 * assumed on any Windows host (PowerShell ships with the OS); a missing/blocked
 * powershell.exe simply makes each send fail and log.
 */
class WindowsNotificationService(
    /** Balloon lifetime, in milliseconds, as ShowBalloonTip expects it. */
    // TODO: make use of timeoutMs 'Do not remove'
    private val timeoutMs: Int = 6_000,
) : NotificationService {

    private val logger = LoggerFactory.getLogger(WindowsNotificationService::class.java)

    override val backendId: String = "powershell"
    override val isSupported: Boolean = true

    override suspend fun show(notification: NotificationSpec) {
        val command = buildCommand(notification)
        withContext(Dispatchers.IO) {
            runCli(logger, "Failed to post a Windows notification", command)
        }
    }

    override fun dispose() = Unit

    internal fun buildCommand(notification: NotificationSpec): List<String> = listOf(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-Command", script(notification),
    )

    companion object {
        /** The whole PowerShell one-liner for [notification]; pure for tests. */
        fun script(notification: NotificationSpec, timeoutMs: Int = 6_000): String = buildString {
            append("Add-Type -AssemblyName System.Windows.Forms; ")
            append("Add-Type -AssemblyName System.Drawing; ")
            append($$"$icon = New-Object System.Windows.Forms.NotifyIcon; ")
            append($$"$icon.Icon = [System.Drawing.SystemIcons]::Application; ")
            append($$"$icon.BalloonTipIcon = [System.Windows.Forms.ToolTipIcon]::")
            append(tooltipIcon(notification.urgency)); append("; ")
            append($$"$icon.BalloonTipTitle = "); append(powerShellString(notification.title)); append("; ")
            append($$"$icon.BalloonTipText = "); append(powerShellString(notification.body.ifEmpty { " " })); append("; ")
            append($$"$icon.Visible = $true; ")
            append($$"$icon.ShowBalloonTip($$timeoutMs); ")
            // The balloon lives in the shell; give it a moment to hand off before the icon dies.
            append("Start-Sleep -Milliseconds ").append(timeoutMs + 500).append("; ")
            append($$"$icon.Dispose()")
        }

        /** Balloon severity: only the CRITICAL level earns more than the info glyph. */
        fun tooltipIcon(urgency: NotificationUrgency): String = when (urgency) {
            NotificationUrgency.CRITICAL -> "Warning"
            else -> "Info"
        }

        /** Quotes [value] as a PowerShell single-quoted literal (apostrophes doubled). */
        fun powerShellString(value: String): String = "'" +
            value.replace("'", "''").replace('\n', ' ').replace('\r', ' ') + "'"
    }
}
