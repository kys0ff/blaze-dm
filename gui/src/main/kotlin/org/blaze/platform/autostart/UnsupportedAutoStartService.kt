package org.blaze.platform.autostart

/** Fallback for platforms with no autostart mechanism we know how to drive. */
class UnsupportedAutoStartService : AutoStartService {
    override val isSupported: Boolean = false
    override fun isEnabled(): Boolean = false
    override fun enable(): Result<Unit> = Result.failure(UnsupportedOperationException("Autostart is not supported on this platform"))
    override fun disable(): Result<Unit> = Result.failure(UnsupportedOperationException("Autostart is not supported on this platform"))
}
