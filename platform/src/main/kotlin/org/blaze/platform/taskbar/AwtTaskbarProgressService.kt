package org.blaze.platform.taskbar

import org.slf4j.LoggerFactory
import java.awt.Taskbar

/**
 * [TaskbarProgressService] for the desktops whose JDK ships a working taskbar API -
 * macOS (Dock) and Windows (taskbar button) - driving it through [Taskbar.setProgressValue].
 *
 * [isSupported] reflects both that the platform exposes a taskbar at all and that it
 * can render a progress value, so where the JDK leaves progress unimplemented this
 * degrades to a no-op and the caller simply never shows a bar.
 */
class AwtTaskbarProgressService : TaskbarProgressService {

    private val logger = LoggerFactory.getLogger(AwtTaskbarProgressService::class.java)

    private val taskbar: Taskbar? = runCatching {
        if (Taskbar.isTaskbarSupported()) {
            Taskbar.getTaskbar().takeIf { it.isSupported(Taskbar.Feature.PROGRESS_VALUE) }
        } else {
            null
        }
    }.onFailure { logger.warn("Taskbar progress probe failed", it) }.getOrNull()

    override val isSupported: Boolean get() = taskbar != null

    override fun setProgress(progress: Float) {
        val bar = taskbar ?: return
        val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
        runCatching { bar.setProgressValue(percent) }
            .onFailure { logger.warn("Failed to set the taskbar progress", it) }
    }

    override fun clear() {
        val bar = taskbar ?: return
        runCatching { bar.setProgressValue(0) }
            .onFailure { logger.warn("Failed to clear the taskbar progress", it) }
    }

    override fun dispose() = clear()
}
