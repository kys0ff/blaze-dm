package org.blaze.platform.taskbar

/** Fallback for platforms with no taskbar-progress mechanism we know how to drive. */
class UnsupportedTaskbarProgressService : TaskbarProgressService {
    override val isSupported: Boolean = false
    override fun setProgress(progress: Float) = Unit
    override fun clear() = Unit
    override fun dispose() = Unit
}
