package org.blaze.platform.autostart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives [AutoStartController] with a fake [AutoStartService] to pin down when the
 * OS is allowed to be touched: only [applyLive] on explicit demand and [syncTo] on
 * actual drift - never as a no-op check's side effect.
 */
class AutoStartControllerTest {

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
    }
}
