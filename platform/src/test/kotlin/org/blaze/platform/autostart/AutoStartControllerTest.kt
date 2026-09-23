package org.blaze.platform.autostart

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives [AutoStartController] with a fake [AutoStartService] to pin down when the
 * OS is allowed to be touched: only [AutoStartController.applyLive] on explicit demand, [AutoStartController.syncTo]
 * on actual drift, and [AutoStartController.pruneStaleRegistration] on a registration the desktop can
 * no longer launch - never as a no-op check's side effect.
 */
class AutoStartControllerTest {

    private class FakeAutoStartService(
        override val isSupported: Boolean = true,
        private val command: String? = null,
    ) : AutoStartService {
        var enabled = false
        var enableCalls = 0
        var disableCalls = 0
        var disableFailure: Throwable? = null

        override fun isEnabled(): Boolean = enabled
        override fun registrationCommand(): String? = command
        override fun enable(): Result<Unit> {
            enableCalls++
            enabled = true
            return Result.success(Unit)
        }

        override fun disable(): Result<Unit> {
            disableCalls++
            enabled = false
            return disableFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }
    }

    @Test
    fun applyLivePushesDesiredStateToTheOs() {
        val service = FakeAutoStartService()
        val controller = AutoStartController(service)

        controller.applyLive(true)
        assertTrue(service.enabled)

        controller.applyLive(false)
        assertFalse(service.enabled)
    }

    @Test
    fun syncToRepairsDriftButIsQuietWhenAlreadyConsistent() {
        val drifted = FakeAutoStartService().apply { enabled = false }
        AutoStartController(drifted).syncTo(true)
        assertTrue(drifted.enabled, "sync must re-register when the OS entry went missing")

        val consistent = FakeAutoStartService().apply { enabled = true }
        AutoStartController(consistent).syncTo(true)
        assertEquals(0, consistent.enableCalls + consistent.disableCalls)
    }

    @Test
    fun unsupportedServiceMakesEveryOperationANoOp() {
        val controller = AutoStartController(FakeAutoStartService(isSupported = false))

        controller.applyLive(true)
        controller.syncTo(true)

        assertFalse(controller.isSupported)
        assertFalse(controller.pruneStaleRegistration())
    }

    @Test
    fun pruneDropsTheJvmADevelopmentSessionRegistered() {
        // The residue of "run at startup" flipped on during debugging: a login item that can only fail.
        val service = FakeAutoStartService(command = "/home/dev/.jdks/jbr-21/bin/java").apply { enabled = true }

        assertTrue(AutoStartController(service).pruneStaleRegistration())

        assertEquals(1, service.disableCalls)
        assertFalse(service.enabled, "the failing login item is gone")
        assertEquals(0, service.enableCalls, "pruning never registers anything")
    }

    @Test
    fun pruneDropsALoginItemWhoseLauncherWasUninstalled() {
        val service = FakeAutoStartService(command = "/opt/Blaze-0.9.0/bin/Blaze").apply { enabled = true }

        assertTrue(AutoStartController(service).pruneStaleRegistration())

        assertEquals(1, service.disableCalls)
    }

    @Test
    fun pruneLeavesAnythingItCannotProveDeadAlone() {
        val live = Files.createTempFile("blaze-login-item", ".bin")
        val kept = listOf(
            live.toString() to "a launcher that exists",
            "/tmp/.mount_BlazeQk1z2f/Blaze" to "an AppImage mount, which is gone between runs by design",
            "blaze" to "a PATH-relative command, which must not be guessed at",
            """C:\Program Files\Blaze\bin\Blaze.exe""" to "a Windows path, not absolute on this host",
            null to "a registration that cannot be read back",
        )
        try {
            kept.forEach { (command, reason) ->
                val service = FakeAutoStartService(command = command).apply { enabled = true }

                assertFalse(AutoStartController(service).pruneStaleRegistration(), "kept: $reason")
                assertEquals(0, service.disableCalls, "kept: $reason")
            }
        } finally {
            Files.deleteIfExists(live)
        }
    }

    @Test
    fun pruneReportsFailureWhenTheOsRefusesToLetGo() {
        val service = FakeAutoStartService(command = "/home/dev/.jdks/jbr-21/bin/java")
            .apply { disableFailure = IllegalStateException("access denied") }

        assertFalse(AutoStartController(service).pruneStaleRegistration(), "a stuck registration is reported, not hidden")
    }

    @Test
    fun isRegisteredReflectsTheOsStateAndNeverTheWish() {
        val registered = FakeAutoStartService().apply { enabled = true }
        val unregistered = FakeAutoStartService()

        assertTrue(AutoStartController(registered).isRegistered())
        assertFalse(AutoStartController(unregistered).isRegistered())
        assertFalse(
            AutoStartController(FakeAutoStartService(isSupported = false).apply { enabled = true }).isRegistered(),
            "a platform with no mechanism has nothing registered, whatever its state claims",
        )
        assertEquals(0, registered.enableCalls + registered.disableCalls, "asking must not write")
    }
}
