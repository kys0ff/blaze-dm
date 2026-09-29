package org.blaze.notifications.api

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gate is the whole "only when the app isn't in sight" contract, so the
 * publisher's decision table is pinned against a recording fake - no real backend,
 * no platform dependency.
 */
class NotificationPublisherTest {

    private class RecordingService(supported: Boolean = true) : NotificationService {
        val shown = mutableListOf<NotificationSpec>()
        override val backendId = "recording"
        override val isSupported = supported
        override suspend fun show(notification: NotificationSpec) {
            shown += notification
        }

        override fun dispose() = Unit
    }

    private val spec = NotificationSpec(title = "Done", body = "file.iso")

    @Test
    fun `an open gate lets the notification through`() = runTest {
        val service = RecordingService()
        NotificationPublisher(service, NotificationGate.OPEN).publish(spec)
        assertEquals(listOf(spec), service.shown)
    }

    @Test
    fun `a closed gate suppresses the notification entirely`() = runTest {
        val service = RecordingService()
        NotificationPublisher(service, NotificationGate { false }).publish(spec)
        assertEquals(emptyList(), service.shown)
    }

    @Test
    fun `the gate sees the spec, so severity can override suppression`() = runTest {
        val service = RecordingService()
        // Deliver critical even when "in sight", suppress the rest.
        val gate = NotificationGate { it.urgency == NotificationUrgency.CRITICAL }
        val publisher = NotificationPublisher(service, gate)

        publisher.publish(spec)
        publisher.publish(spec.copy(urgency = NotificationUrgency.CRITICAL))

        assertEquals(1, service.shown.size)
        assertEquals(NotificationUrgency.CRITICAL, service.shown.single().urgency)
    }

    @Test
    fun `an unsupported backend never reaches the gate and drops the request`() = runTest {
        var gateConsulted = false
        val service = RecordingService(supported = false)
        val publisher = NotificationPublisher(service, NotificationGate { gateConsulted = true; true })

        publisher.publish(spec)

        assertEquals(emptyList(), service.shown)
        assertEquals(false, gateConsulted, "the supported check must short-circuit first")
    }
}
