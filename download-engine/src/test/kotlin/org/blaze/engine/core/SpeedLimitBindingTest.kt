package org.blaze.engine.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.blaze.engine.persistence.DownloadRepository
import org.blaze.engine.settings.EngineSettingsRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A "global" speed limit has to be both global and live. The throttle is owned by the engine, so
 * editing it in the settings has to reach the transfers that are already running instead of only
 * the next download to start - which is what per-download throttling silently failed to do.
 *
 * The manager is driven on a real dispatcher because the binding is an always-on collector
 * coroutine; virtual time would never let it observe the settings flow.
 */
class SpeedLimitBindingTest {

    @Test
    fun `the engine-wide throttle follows the settings while downloads are running`() {
        val tempDir = Files.createTempDirectory("blaze-speed-limit-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settingsRepository = EngineSettingsRepository(tempDir)
        val manager = DownloadManager(
            scope = scope,
            repository = DownloadRepository(tempDir),
            settingsRepository = settingsRepository
        )

        try {
            runBlocking {
                assertEquals(0L, manager.speedLimitBytesPerSec, "throttling must be off by default")

                settingsRepository.updateSettings {
                    it.copy(globalSpeedLimitEnabled = true, globalSpeedLimitKbps = 2048)
                }
                await(2048L * 1024, "enabling the limit did not install a ceiling") {
                    manager.speedLimitBytesPerSec
                }

                settingsRepository.updateSettings { it.copy(globalSpeedLimitKbps = 512) }
                await(512L * 1024, "changing the rate did not move the ceiling") {
                    manager.speedLimitBytesPerSec
                }

                settingsRepository.updateSettings { it.copy(globalSpeedLimitEnabled = false) }
                await(0L, "disabling the limit left a ceiling in place") { manager.speedLimitBytesPerSec }
            }
        } finally {
            runBlocking { manager.shutdown() }
            scope.cancel()
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun await(expected: Long, message: String, actual: () -> Long) {
        val deadline = System.currentTimeMillis() + 5_000
        while (actual() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(expected, actual(), message)
    }
}
