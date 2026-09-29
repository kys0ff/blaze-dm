package org.blaze.presentation.application

import org.blaze.download.api.ApiErrorBody
import org.blaze.download.api.ApiErrorCode
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.i18n.EnStrings
import org.blaze.notifications.api.NotificationUrgency
import org.blaze.presentation.screens.downloads.DownloadAdvisory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the two Blaze-specific decisions layered on top of the reusable `:notifications`
 * library: *when* to speak (only while the window is out of sight) and *what* to say
 * (the same localized advisory wording the in-app balloons use, with a severity that
 * matches how much the user is needed).
 */
class AppSystemNotificationsTest {

    private fun download(
        state: DownloadState = DownloadState.COMPLETED,
        name: String = "file.zip",
        error: ApiErrorBody? = null,
    ) = Download(
        id = "d1",
        name = name,
        url = "https://example.com/$name",
        totalSize = 1_048_576,
        downloadedSize = 1_048_576,
        speed = 0,
        state = state,
        addedAt = 0L,
        savePath = "/home/user/Downloads",
        error = error,
    )

    // ---- the "in sight" gate ----

    @Test
    fun `a visible, focused window suppresses the notification`() {
        val attention = AppWindowAttention(windowVisible = { true }, appInFocus = { true })
        assertTrue(attention.isInSight)
        assertFalse(attention.gate.shouldDeliver(spec()))
    }

    @Test
    fun `a hidden window delivers, no matter the focus`() {
        val attention = AppWindowAttention(windowVisible = { false }, appInFocus = { true })
        assertFalse(attention.isInSight)
        assertTrue(attention.gate.shouldDeliver(spec()))
    }

    @Test
    fun `a visible window in the background delivers`() {
        val attention = AppWindowAttention(windowVisible = { true }, appInFocus = { false })
        assertFalse(attention.isInSight)
        assertTrue(attention.gate.shouldDeliver(spec()))
    }

    @Test
    fun `the gate reads live state on every check`() {
        var visible = true
        val attention = AppWindowAttention(windowVisible = { visible }, appInFocus = { true })
        assertFalse(attention.gate.shouldDeliver(spec()))
        visible = false
        assertTrue(attention.gate.shouldDeliver(spec()))
    }

    // ---- advisory -> localized notice mapping ----

    @Test
    fun `a completion is a normal banner with the shared wording`() {
        val spec = DownloadAdvisory.Completed(download()).systemNotificationFor(EnStrings)
        val adv = EnStrings.downloads.advisories
        assertEquals(adv.titleCompleted, spec.title)
        assertEquals(adv.completed("file.zip"), spec.body)
        assertEquals(NotificationUrgency.NORMAL, spec.urgency)
    }

    @Test
    fun `a failure is critical and names the friendly reason`() {
        val failing = download(
            state = DownloadState.FAILED,
            error = ApiErrorBody(ApiErrorCode.NETWORK_ERROR, "connection reset"),
        )
        val spec = DownloadAdvisory.Failed(failing).systemNotificationFor(EnStrings)
        val adv = EnStrings.downloads.advisories
        assertEquals(adv.titleFailed, spec.title)
        assertEquals(adv.failed("file.zip", EnStrings.errors.networkFailure("connection reset")), spec.body)
        assertEquals(NotificationUrgency.CRITICAL, spec.urgency)
    }

    @Test
    fun `a failure with no error body falls back to the unknown label`() {
        val failing = download(state = DownloadState.FAILED, error = null)
        val spec = DownloadAdvisory.Failed(failing).systemNotificationFor(EnStrings)
        val adv = EnStrings.downloads.advisories
        assertEquals(adv.failed("file.zip", EnStrings.common.unknown), spec.body)
    }

    @Test
    fun `a restart quotes the lost percentage`() {
        val spec = DownloadAdvisory.Restarted(download(), lostPercent = 42).systemNotificationFor(EnStrings)
        val adv = EnStrings.downloads.advisories
        assertEquals(adv.titleRestarted, spec.title)
        assertEquals(adv.restarted("file.zip", 42), spec.body)
        assertEquals(NotificationUrgency.NORMAL, spec.urgency)
    }

    private fun spec() = org.blaze.notifications.api.NotificationSpec("t", "b")
}
