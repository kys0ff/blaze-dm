package org.blaze.platform.autostart

import org.blaze.data.AppSettings
import org.blaze.data.AppSettingsRepository
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives [AutoStartCoordinator] with a fake [AutoStartService] to pin down when the
 * OS is allowed to be touched: only on Apply/OK (live) and when reconciling drift —
 * never as a side effect of browsing the settings screen.
 */
class AutoStartCoordinatorTest {

    private val tempDir = Files.createTempDirectory("blaze-autostart-coordinator-test")

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private class FakeAutoStartService(override val isSupported: Boolean = true) : AutoStartService {
        var enabled = false
        var enableCalls = 0
        var disableCalls = 0

        override fun isEnabled(): Boolean = enabled
        override fun enable(): Result<Unit> {
            enableCalls++
            enabled = true
            return Result.success(Unit)
        }

        override fun disable(): Result<Unit> {
            disableCalls++
            enabled = false
            return Result.success(Unit)
        }
    }

    private fun repository(initial: AppSettings? = null): AppSettingsRepository {
        val repository = AppSettingsRepository(tempDir)
        if (initial != null) runBlocking { repository.updateSettings { initial } }
        return repository
    }

    @Test
    fun applyLivePushesDesiredStateToTheOs(): Unit = runBlocking {
        val service = FakeAutoStartService()
        val coordinator = AutoStartCoordinator(repository(), service)

        coordinator.applyLive(true)
        assertTrue(service.enabled)

        coordinator.applyLive(false)
        assertFalse(service.enabled)
    }

    @Test
    fun revertToPersistedUndoesAnUnpersistedApply(): Unit = runBlocking {
        // Disk says "off"; the user hit Apply with "on" (live OS write), then left via Cancel.
        val appSettings = AppSettings(runAtStartup = false)
        val service = FakeAutoStartService().apply { enabled = true }
        val coordinator = AutoStartCoordinator(repository(appSettings), service)

        coordinator.revertToPersisted()

        assertFalse(service.enabled, "OS registration must follow the persisted setting")
    }

    @Test
    fun syncRepairsDriftButIsQuietWhenAlreadyConsistent(): Unit = runBlocking {
        val appSettings = AppSettings(runAtStartup = true)
        val drifted = FakeAutoStartService().apply { enabled = false }
        AutoStartCoordinator(repository(appSettings), drifted).sync()
        assertTrue(drifted.enabled, "startup sync must re-register when the OS entry went missing")

        val consistent = FakeAutoStartService().apply { enabled = true }
        AutoStartCoordinator(repository(appSettings), consistent).sync()
        assertEquals(0, consistent.enableCalls + consistent.disableCalls)
    }

    @Test
    fun unsupportedServiceMakesEveryOperationANoOp(): Unit = runBlocking {
        val coordinator = AutoStartCoordinator(repository(), FakeAutoStartService(isSupported = false))

        coordinator.applyLive(true)
        coordinator.revertToPersisted()
        coordinator.sync()

        assertFalse(coordinator.isSupported)
    }
}
