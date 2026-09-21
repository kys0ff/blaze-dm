package org.blaze.presentation.util

import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("org.blaze.presentation.util")

/**
 * Opens a directory in the OS file manager. Prefers java.awt.Desktop (works across
 * platforms under X11/Aqua) and falls back to the platform CLI opener. Returns false
 * when no mechanism succeeded; the caller decides how to surface that to the user.
 */
fun openInFileManager(dir: Path): Boolean {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(dir.toFile())
            return true
        }
    }

    val osName = System.getProperty("os.name").lowercase()
    val command = when {
        osName.contains("win") -> listOf("explorer", dir.toString())
        osName.contains("mac") -> listOf("open", dir.toString())
        else -> listOf("xdg-open", dir.toString())
    }

    return runCatching {
        ProcessBuilder(command).start()
        true
    }.getOrElse {
        logger.warn("Failed to open {} in the system file manager", dir, it)
        false
    }
}
