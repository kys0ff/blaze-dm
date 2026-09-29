package org.blaze.data

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the deferred persistence semantics the settings screen relies on:
 * in-memory edits are live but never touch disk, OK persists, and revert restores
 * exactly what is on disk.
 */
class AppSettingsRepositoryTest {

    private val tempDir = Files.createTempDirectory("blaze-app-settings-test")

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun defaultsAreUsedWhenNoFileExists(): Unit = runBlocking {
        val repository = AppSettingsRepository(tempDir)

        assertEquals(AppSettings(), repository.settings.value)
        assertEquals(AppSettings(), repository.persisted)
        assertTrue(repository.settings.value.trayEnabled)
        assertFalse(repository.settings.value.runAtStartup)
        // Desktop notices are on by default, but only while Blaze is out of sight.
        assertTrue(repository.settings.value.desktopNotificationsEnabled)
        assertTrue(repository.settings.value.notifyOnCompleted)
        assertTrue(repository.settings.value.notifyOnFailed)
        assertTrue(repository.settings.value.notifyOnRestarted)
        assertFalse(repository.settings.value.notifyWhileWindowInSight)
    }

    @Test
    fun notificationPreferencesSurviveAReload(): Unit = runBlocking {
        val repository = AppSettingsRepository(tempDir)

        repository.updateSettings {
            it.copy(
                notifyOnCompleted = false,
                notifyOnRestarted = false,
                notifyWhileWindowInSight = true,
            )
        }

        val reloaded = AppSettingsRepository(tempDir).settings.value
        assertFalse(reloaded.notifyOnCompleted)
        assertTrue(reloaded.notifyOnFailed)
        assertFalse(reloaded.notifyOnRestarted)
        assertTrue(reloaded.notifyWhileWindowInSight)
    }

    @Test
    fun persistedSettingsSurviveAReload(): Unit = runBlocking {
        val repository = AppSettingsRepository(tempDir)
        val desired = AppSettings(trayEnabled = false, minimizeToTrayOnClose = false, runAtStartup = true)

        repository.updateSettings { desired }

        val reloaded = AppSettingsRepository(tempDir)
        assertEquals(desired, reloaded.settings.value)
        assertTrue(Files.exists(tempDir.resolve("app.json")))
    }

    @Test
    fun inMemoryUpdatesAreLiveButRevertedWithoutPersist(): Unit = runBlocking {
        val repository = AppSettingsRepository(tempDir)

        // Apply: live change, nothing on disk, persisted snapshot untouched.
        repository.updateSettingsInMemory { it.copy(runAtStartup = true) }
        assertTrue(repository.settings.value.runAtStartup)
        assertFalse(repository.persisted.runAtStartup)
        assertFalse(Files.exists(tempDir.resolve("app.json")))

        // Leaving the screen: revert restores the disk value.
        repository.revertToPersisted()
        assertFalse(repository.settings.value.runAtStartup)
    }

    @Test
    fun unknownKeysInStoredJsonAreIgnored(): Unit = runBlocking {
        Files.writeString(
            tempDir.resolve("app.json"),
            """{"trayEnabled": false, "futureOption": 42}"""
        )

        val repository = AppSettingsRepository(tempDir)

        assertFalse(repository.settings.value.trayEnabled)
        assertEquals(AppSettings().minimizeToTrayOnClose, repository.settings.value.minimizeToTrayOnClose)
    }
}
