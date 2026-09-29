package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.blaze.data.AppSettings
import org.blaze.data.AppSettingsRepository
import org.blaze.domain.models.Download
import org.blaze.domain.models.DownloadState
import org.blaze.domain.repository.DownloadRepository
import org.blaze.i18n.BlazeStrings
import org.blaze.notifications.api.NotificationGate
import org.blaze.notifications.api.NotificationPublisher
import org.blaze.notifications.api.NotificationService
import org.blaze.notifications.api.NotificationSpec
import org.blaze.notifications.api.NotificationUrgency
import org.blaze.presentation.screens.downloads.DownloadAdvisory
import org.blaze.presentation.screens.downloads.diffDownloadTransitions
import org.blaze.presentation.util.toFriendlyMessage
import org.koin.compose.koinInject
import java.awt.KeyboardFocusManager
import kotlin.time.Duration.Companion.milliseconds

/**
 * Decides when the user can already see Blaze, so desktop notifications fire only in
 * its absence: the window is hidden (tray) *or* no Blaze window currently holds the
 * OS focus counts as "not in sight".
 *
 * Focus is read from AWT at publish time rather than tracked:
 * [KeyboardFocusManager.getActiveWindow] is this application's active window, and it
 * is null exactly while another app owns the foreground. A dialog taking focus still
 * counts as "in sight" - the user is looking at Blaze either way.
 */
class AppWindowAttention(
    private val windowVisible: () -> Boolean,
    private val appInFocus: () -> Boolean = {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null
    },
) {
    /** True while the user can see the app - a system notification would be noise. */
    val isInSight: Boolean get() = windowVisible() && appInFocus()

    /** The gate the publisher consults before every desktop send. */
    val gate: NotificationGate = NotificationGate { !isInSight }
}

/**
 * Blaze's desktop-notice policy: the notifications checkboxes from [AppSettings] translated into
 * the two questions the delivery path asks - *is this kind of event wanted at all*, and *may the
 * desktop be spoken to right now*.
 *
 * Settings are read through a lambda instead of captured, so ticking a box takes effect on the very
 * next event without restarting the watcher, and an unpersisted Apply is honoured exactly like the
 * other shell options.
 */
internal class AppNotificationPreferences(
    private val currentSettings: () -> AppSettings,
    private val attention: AppWindowAttention,
) {
    /**
     * Whether the user asked for [advisory]'s kind of event. Checked before anything is waited on,
     * so a kind that was switched off never costs the failure retry grace.
     */
    fun isWanted(advisory: DownloadAdvisory): Boolean {
        val settings = currentSettings()
        if (!settings.desktopNotificationsEnabled) return false
        return when (advisory) {
            is DownloadAdvisory.Completed -> settings.notifyOnCompleted
            is DownloadAdvisory.Failed -> settings.notifyOnFailed
            is DownloadAdvisory.Restarted -> settings.notifyOnRestarted
        }
    }

    /**
     * The gate consulted at send time: the master switch, plus the "keep quiet while Blaze is in
     * sight" preference - [attention] decides that silence unless the user opted into being
     * notified with the window right in front of them.
     */
    val gate: NotificationGate = NotificationGate { notification ->
        val settings = currentSettings()
        settings.desktopNotificationsEnabled &&
            (settings.notifyWhileWindowInSight || attention.gate.shouldDeliver(notification))
    }
}

/**
 * Mirrors the downloads list's lifecycle transitions (completed / failed / restarted)
 * onto the *desktop* notification surface through the reusable `:notifications`
 * library, complementing the in-window balloons the downloads screen shows.
 *
 * The event semantics deliberately match [org.blaze.presentation.screens.downloads.DownloadsScreenModel]:
 * the first snapshot is only remembered (restored downloads are history, not news), and
 * a failure waits out [RETRY_GRACE] - automatic retries hop through FAILED on the way
 * back to QUEUED, and an interim failure the user is not even notified about in-window
 * must never reach the desktop.
 *
 * Wording comes from the same localized advisories strings the in-app balloons use, so
 * a notice reads identically wherever it lands. Which of those transitions may go out at all is
 * the user's call, made in the Notifications settings category and applied by
 * [AppNotificationPreferences].
 */
@Composable
fun AppSystemNotifications(shell: AppShell, strings: BlazeStrings) {
    val service = koinInject<NotificationService>()
    val repository = koinInject<DownloadRepository>()
    val appSettingsRepository = koinInject<AppSettingsRepository>()

    val preferences = remember(appSettingsRepository, shell) {
        AppNotificationPreferences(
            currentSettings = { appSettingsRepository.settings.value },
            attention = AppWindowAttention({ shell.windowVisible.value }),
        )
    }
    val publisher = remember(service, preferences) {
        NotificationPublisher(service, preferences.gate)
    }

    LaunchedEffect(publisher, repository, strings) {
        watchTransitions(repository, publisher, strings, preferences, this)
    }
}

/**
 * The watching loop, kept free of Compose so the transition semantics stay unit-testable:
 * collects the shared downloads stream, diffs it, and delivers each notice on its own
 * job (waiting out a retry must not stall the stream).
 */
internal suspend fun watchTransitions(
    repository: DownloadRepository,
    publisher: NotificationPublisher,
    strings: BlazeStrings,
    preferences: AppNotificationPreferences,
    scope: CoroutineScope,
) {
    val latest = MutableStateFlow<List<Download>>(emptyList())
    var previous: List<Download>? = null
    val jobs: MutableList<Job> = mutableListOf()

    repository.downloads.collect { current ->
        val before = previous
        previous = current
        latest.value = current
        if (before == null) return@collect

        for (advisory in diffDownloadTransitions(before, current)) {
            // A kind the user switched off is dropped here, not after the retry grace.
            if (!preferences.isWanted(advisory)) continue
            // Gate check happens inside the job, at delivery time: a notice held back by
            // the retry grace may see the window raised or hidden while it waits.
            jobs += scope.launch { deliver(advisory, latest, publisher, strings, preferences) }
        }
    }
    // Notices still in flight when the stream ends (app leaving composition) get a moment
    // to finish; the D-Bus/CLI sends are short and bounded by their own timeouts anyway.
    jobs.joinAll()
}

private suspend fun deliver(
    advisory: DownloadAdvisory,
    latest: MutableStateFlow<List<Download>>,
    publisher: NotificationPublisher,
    strings: BlazeStrings,
    preferences: AppNotificationPreferences,
) {
    var notice = advisory
    if (advisory is DownloadAdvisory.Failed) {
        delay(RETRY_GRACE_MILLIS.milliseconds)
        val live = latest.value.find { it.id == advisory.download.id } ?: return
        if (live.state != DownloadState.FAILED) return
        // Re-word the notice from the fresh row: its name/error may have settled during the grace.
        notice = advisory.copy(download = live)
        // Still unwanted by the time the grace ran out? Say nothing.
        if (!preferences.isWanted(notice)) return
    }
    publisher.publish(notice.systemNotificationFor(strings))
}

/**
 * Turns an advisory into the localized desktop notice. A failure is the only CRITICAL
 * case - it needs the user, not the app, to act; completions and forced restarts are
 * routine NORMAL banners. Wording is shared with the in-app balloons.
 */
internal fun DownloadAdvisory.systemNotificationFor(strings: BlazeStrings): NotificationSpec {
    val advisories = strings.downloads.advisories
    return when (this) {
        is DownloadAdvisory.Completed ->
            NotificationSpec(advisories.titleCompleted, advisories.completed(download.name))

        is DownloadAdvisory.Restarted ->
            NotificationSpec(advisories.titleRestarted, advisories.restarted(download.name, lostPercent))

        is DownloadAdvisory.Failed ->
            NotificationSpec(
                title = advisories.titleFailed,
                body = advisories.failed(
                    download.name,
                    download.error?.toFriendlyMessage(strings) ?: strings.common.unknown,
                ),
                urgency = NotificationUrgency.CRITICAL,
            )
    }
}

private const val RETRY_GRACE_MILLIS = 5_000L
