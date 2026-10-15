package org.blaze.presentation.screens.settings

import androidx.compose.ui.text.input.TextFieldValue
import org.blaze.data.StoredKeyBinding
import org.blaze.engine.api.DownloadPriority
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.engine.settings.LogLevel
import org.blaze.engine.settings.ThemeMode
import org.blaze.presentation.screens.settings.state.SettingsCategory

sealed interface SettingsEvent {
    data class ChangeCategory(val category: SettingsCategory) : SettingsEvent
    
    // Concurrency & Queueing
    data class UpdateMaxConcurrentDownloads(val value: TextFieldValue) : SettingsEvent
    data class UpdateMaxConnectionsPerDownload(val value: TextFieldValue) : SettingsEvent
    data class UpdateDefaultDownloadPriority(val priority: DownloadPriority) : SettingsEvent
    
    // Bandwidth
    data class UpdateSpeedLimitEnabled(val enabled: Boolean) : SettingsEvent
    data class UpdateSpeedLimitKbps(val value: TextFieldValue) : SettingsEvent
    data class UpdateSnailModeEnabled(val enabled: Boolean) : SettingsEvent
    data class UpdateSnailSpeedKbps(val value: TextFieldValue) : SettingsEvent
    
    // Retries
    data class UpdateAutoRetryFailed(val enabled: Boolean) : SettingsEvent
    data class UpdateMaxRetries(val value: TextFieldValue) : SettingsEvent
    data class UpdateRetryDelaySeconds(val value: TextFieldValue) : SettingsEvent
    data class UpdateExponentialBackoff(val enabled: Boolean) : SettingsEvent

    // Network & Protocol
    data class UpdateUserAgent(val value: TextFieldValue) : SettingsEvent
    data class UpdateMaxRedirects(val value: TextFieldValue) : SettingsEvent

    // Torrent
    data class UpdateMaxPeerConnections(val value: TextFieldValue) : SettingsEvent
    data class UpdateEnableSeeding(val enabled: Boolean) : SettingsEvent
    data class UpdateSeedTimeLimitMinutes(val value: TextFieldValue) : SettingsEvent
    data class UpdateUnlimitedSeeding(val enabled: Boolean) : SettingsEvent
    data class UpdateSeedRatioLimit(val value: TextFieldValue) : SettingsEvent
    /** Opt-in: preview a `.torrent` download link's files and add it as a local torrent (app-settings draft). */
    data class UpdateTorrentFilePreview(val enabled: Boolean) : SettingsEvent

    // Appearance
    data class UpdateThemeMode(val mode: ThemeMode) : SettingsEvent
    /** UI language as a BlazeLocale code; buffered in the app-settings draft like the rest. */
    data class UpdateLanguage(val code: String) : SettingsEvent

    /** Interface font family id from the IslandFontFamilies catalogue (app-settings draft). */
    data class UpdateUiFontFamily(val id: String) : SettingsEvent

    /** Monospace (console) font family id from the same catalogue (app-settings draft). */
    data class UpdateMonoFontFamily(val id: String) : SettingsEvent

    /** UI text size percentage; clamped into the catalogue's safe range when buffered. */
    data class UpdateUiTextScalePercent(val percent: Int) : SettingsEvent
    /** The frame's ambient accent glow; also buffered in the app-settings draft. */
    data class UpdateAmbientGlowEnabled(val enabled: Boolean) : SettingsEvent
    
    // Startup
    data class UpdateResumeDownloadsOnStartup(val enabled: Boolean) : SettingsEvent
    data class UpdateStartQueuedOnStartup(val enabled: Boolean) : SettingsEvent

    // System integration (tray / autostart) — buffered in the app-settings draft until Apply/OK
    data class UpdateTrayEnabled(val enabled: Boolean) : SettingsEvent
    data class UpdateMinimizeToTrayOnClose(val enabled: Boolean) : SettingsEvent
    data class UpdateRunAtStartup(val enabled: Boolean) : SettingsEvent
    /** Open hidden in the tray on launch (app-settings draft); only honoured when a tray is available. */
    data class UpdateStartMinimizedInTray(val enabled: Boolean) : SettingsEvent
    data class UpdateClipboardMonitoring(val enabled: Boolean) : SettingsEvent

    // Security & consent — like every app-shell option these are buffered in the app-settings draft,
    // so a prompt switch flips and an approval is revoked only on Apply/OK (and reverts on Cancel).
    // Revoking is draft-only on purpose: a *persisted* approval is what dies, while an in-memory
    // "allow once" grant from the current run is deliberately short-lived already.
    data class UpdateApproveExternalClients(val enabled: Boolean) : SettingsEvent
    data class UpdateApproveExtensions(val enabled: Boolean) : SettingsEvent
    /** Take back a remembered client's "allow always" so it must ask again (or is refused). */
    data class RevokeRememberedClient(val clientId: String) : SettingsEvent
    /** Drop a trusted extension's approval by content hash + kind, so its jar stops loading. */
    data class ForgetTrustedExtension(val sha256: String, val kind: String) : SettingsEvent

    // Desktop notifications — all buffered in the app-settings draft like the rest of the shell
    // options, so nothing reaches the notification service before Apply/OK.
    data class UpdateDesktopNotificationsEnabled(val enabled: Boolean) : SettingsEvent
    data class UpdateNotifyOnCompleted(val enabled: Boolean) : SettingsEvent
    data class UpdateNotifyOnFailed(val enabled: Boolean) : SettingsEvent
    data class UpdateNotifyOnRestarted(val enabled: Boolean) : SettingsEvent
    data class UpdateNotifyWhileInSight(val enabled: Boolean) : SettingsEvent
    
    // Destination & Conflicts
    data class UpdateDefaultDownloadDir(val path: String) : SettingsEvent
    data class UpdateAskWhereToSave(val enabled: Boolean) : SettingsEvent
    data class UpdateFileConflictBehavior(val behavior: FileConflictBehavior) : SettingsEvent

    // Logging
    data class UpdateLogLevel(val level: LogLevel) : SettingsEvent
    data class UpdateFileLoggingEnabled(val enabled: Boolean) : SettingsEvent

    // Keyboard shortcuts — all buffered in the app-settings draft (keyed by BlazeKeymap action name),
    // so a rebind reaches the running keymap only on Apply/OK and reverts on Cancel, like the rest.
    /** Record [binding] as the new chord for [actionName], replacing any override and un-unbinding it. */
    data class SetKeyBinding(val actionName: String, val binding: StoredKeyBinding) : SettingsEvent
    /** Explicitly unbind [actionName] so it fires on no key and prints no accelerator. */
    data class ClearKeyBinding(val actionName: String) : SettingsEvent
    /** Drop [actionName]'s override/unbind so it falls back to its built-in default chord. */
    data class RestoreDefaultKeyBinding(val actionName: String) : SettingsEvent
    /** Restore every action to its built-in default, clearing all overrides and unbinds at once. */
    data object RestoreAllKeyBindings : SettingsEvent

    // Link handlers / extensions (enable/disable and always-ask are buffered until Apply/OK;
    // install/remove/reload act on the extensions directory immediately)
    data class ToggleHandler(val id: String, val enabled: Boolean) : SettingsEvent
    /** Buffer a handler's whole option map in the draft (the option dialog's OK). */
    data class UpdateHandlerOptions(val id: String, val values: Map<String, String>) : SettingsEvent
    data class UpdateAlwaysAskHandler(val enabled: Boolean) : SettingsEvent
    data class InstallExtension(val jarPath: String) : SettingsEvent
    data class RemoveExtension(val id: String) : SettingsEvent
    data object ReloadHandlers : SettingsEvent

    // Themes / theme extensions (selection is buffered until Apply/OK)
    data class SelectTheme(val id: String) : SettingsEvent
    /** Buffer a theme's whole option map in the draft (the theme options dialog's OK). */
    data class UpdateThemeOptions(val id: String, val values: Map<String, String>) : SettingsEvent
    data class InstallTheme(val jarPath: String) : SettingsEvent
    data class RemoveTheme(val id: String) : SettingsEvent
    data object ReloadThemes : SettingsEvent

    // Commit / dismiss
    /** Persist the current draft but keep the screen open. */
    data object Apply : SettingsEvent

    /** Persist the current draft, then close the screen. */
    data object Ok : SettingsEvent

    /** Discard any uncommitted changes and close the screen. */
    data object Cancel : SettingsEvent
}
