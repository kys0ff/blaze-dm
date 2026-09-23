package org.blaze.presentation.screens.downloads

import org.blaze.download.api.ApiErrorBody

/**
 * One-shot side effects the downloads screen performs outside of its state.
 *
 * [ShowError] and [ShowMessage] carry copy written by the command handlers (they quote an exception
 * message, which no string table can pre-translate). The lifecycle notices below deliberately carry
 * data only: the view renders them through [org.blaze.i18n.BlazeStrings], so a completion or a
 * failure stays translatable.
 */
sealed interface DownloadsEffect {
    data class ShowError(val message: String) : DownloadsEffect
    data class ShowMessage(val message: String) : DownloadsEffect

    /** A download finished while the window was open. */
    data class DownloadCompleted(val name: String) : DownloadsEffect

    /** A download gave up; [reason] is the engine's own error, rendered by the view. */
    data class DownloadFailed(val name: String, val reason: ApiErrorBody?) : DownloadsEffect

    /** A transfer began again from zero, discarding [lostPercent] worth of finished work. */
    data class DownloadRestarted(val name: String, val lostPercent: Int) : DownloadsEffect
}
