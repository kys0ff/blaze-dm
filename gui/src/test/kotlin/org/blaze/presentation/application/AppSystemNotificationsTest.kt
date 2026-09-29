package org.blaze.presentation.application

import org.blaze.data.AppSettings
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

    // ---- the notifications settings ----

    /**
     * The user's checkboxes as the delivery path sees them: settings and window state stay mutable
     * so a test can prove both are read live rather than captured at startup.
     */
    private class PreferenceFixture {
        var settings = AppSettings()
        var windowVisible = false
        var appInFocus = false

        val preferences: AppNotificationPreferences
            get() = AppNotificationPreferences({ settings }, AppWindowAttention({ windowVisible }, { appInFocus }))
    }

    @Test
    fun `a switched-off event kind is dropped before anything is sent`() {
        val fixture = PreferenceFixture().apply { settings = settings.copy(notifyOnCompleted = false) }

        assertFalse(fixture.preferences.isWanted(DownloadAdvisory.Completed(download())))
        assertTrue(fixture.preferences.isWanted(DownloadAdvisory.Failed(download(state = DownloadState.FAILED))))
    }

    @Test
    fun `the master switch drops every kind, even a critical failure`() {
        val fixture = PreferenceFixture().apply { settings = settings.copy(desktopNotificationsEnabled = false) }

        assertFalse(fixture.preferences.isWanted(DownloadAdvisory.Completed(download())))
        assertFalse(fixture.preferences.isWanted(DownloadAdvisory.Restarted(download(), lostPercent = 10)))
        assertFalse(fixture.preferences.isWanted(DownloadAdvisory.Failed(download(state = DownloadState.FAILED))))
        assertFalse(fixture.preferences.gate.shouldDeliver(spec()))
    }

    @Test
    fun `an in-sight window stays quiet unless the user opted into it`() {
        val fixture = PreferenceFixture().apply { windowVisible = true; appInFocus = true }
        assertFalse(fixture.preferences.gate.shouldDeliver(spec()))

        fixture.settings = fixture.settings.copy(notifyWhileWindowInSight = true)
        assertTrue(fixture.preferences.gate.shouldDeliver(spec()))
    }

    @Test
    fun `a backgrounded window delivers whatever the kind settings allow`() {
        val fixture = PreferenceFixture().apply { windowVisible = true; appInFocus = false }

        assertTrue(fixture.preferences.gate.shouldDeliver(spec()))
        assertTrue(fixture.preferences.isWanted(DownloadAdvisory.Completed(download())))
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
