package org.blaze.platform.files

import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger("org.blaze.platform.files")

/**
 * Shared primitives for the per-OS [SystemFileService] implementations: a best-effort
 * java.awt.Desktop attempt (works across platforms under X11/Aqua when a desktop session
 * exposes the action) and a fire-and-forget external command runner. The Desktop path is
 * intentionally "try and fall through": a headless or Wayland session where the AWT
 * integration is unavailable returns null so callers can use a native opener instead.
 */
internal object FileOpeners {

    /** Returns true/false when AWT Desktop handled [action], or null when it isn't usable. */
    fun tryDesktop(action: Desktop.Action, path: Path): Boolean? {
        if (!Desktop.isDesktopSupported()) return null
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return null
        if (!runCatching { desktop.isSupported(action) }.getOrDefault(false)) return null
        return runCatching {
            when (action) {
                Desktop.Action.OPEN -> desktop.open(path.toFile())
                else -> return null
            }
            true
        }.getOrElse {
            logger.debug("Desktop {} failed for {}", action, path, it)
            false
        }
    }

    /** Returns true/false when AWT Desktop browsed [url], or null when it isn't usable. */
    fun tryDesktopBrowse(url: String): Boolean? {
        if (!Desktop.isDesktopSupported()) return null
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return null
        if (!runCatching { desktop.isSupported(Desktop.Action.BROWSE) }.getOrDefault(false)) return null
        return runCatching {
            desktop.browse(java.net.URI(url))
            true
        }.getOrElse {
            logger.debug("Desktop BROWSE failed for {}", url, it)
            false
        }
    }

    /**
     * Launches [command] without waiting for it to finish (openers return immediately).
     * Failures are reported through the returned [Result] so the UI can notify the user. Only
     * meaningful for launchers that set a real status - see [launchDetached] for the rest.
     */
    fun launch(command: List<String>): Result<Unit> = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Give the launcher a moment to fail fast (bad binary, no display) without blocking.
        val exited = process.waitFor(5, TimeUnit.SECONDS)
        if (exited && process.exitValue() != 0) {
            val output = process.inputStream.bufferedReader().readText().trim()
            error("Command failed (exit ${process.exitValue()}): $command ${output.take(200)}")
        }
    }.onFailure { logger.warn("Failed to run opener {}", command, it) }

    /**
     * Launches [command] and forgets about it - no waiting, no exit-code check - so only a failure
     * to start the process at all is reported. Required for openers whose status carries no meaning:
     * Windows `explorer.exe` exits non-zero even when it opened the window, and reading that as a
     * failure makes the caller retry and open a second window. Output goes to the null device so the
     * detached child can never block on a full pipe we are no longer reading.
     */
    fun launchDetached(command: List<String>): Result<Unit> = runCatching {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.map { }.onFailure { logger.warn("Failed to run opener {}", command, it) }
}
