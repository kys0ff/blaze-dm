package org.blaze.presentation.screens.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.blaze.data.AppSettings
import org.blaze.data.AppSettingsRepository
import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.settings.EngineSettingsRepository
import org.blaze.notifications.api.NotificationService
import org.blaze.platform.autostart.AutoStartCoordinator
import org.blaze.presentation.screens.settings.state.HandlerUiState
import org.blaze.presentation.screens.settings.state.ThemeUiState
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.ResolverSource
import org.blaze.theming.core.ThemeRegistry
import org.blaze.theming.core.ThemeSettingsRepository
import org.blaze.theming.core.ThemeSource
import org.blaze.tray.api.TrayService
import java.nio.file.Path

/**
 * Environment capabilities. The corresponding Settings options are hidden when the value is false, so
 * the screen never offers a control its host cannot honour.
 */
data class SettingsSupportFlags(
    val traySupported: Boolean,
    val autoStartSupported: Boolean,
    val notificationsSupported: Boolean,
)

/** The handler list joined with its resolver settings; emitted whenever either side changes. */
data class HandlersSnapshot(
    val handlers: List<HandlerUiState>,
    val alwaysAsk: Boolean,
    val extensionDir: String,
)

/** The theme list joined with the selected id and the theme settings; a hot three-way combine. */
data class ThemesSnapshot(
    val themes: List<ThemeUiState>,
    val themesDir: String,
)

/**
 * The buffered Settings draft, narrowed to just the fields the repositories and registries need to
 * apply or persist it. Keeping it separate from [org.blaze.presentation.screens.settings.state.SettingsState]
 * lets [SettingsUseCases] stay free of presentation-only concerns (per-field text values, their errors,
 * the currently selected category).
 */
data class SettingsDraft(
    val settings: DownloadSettings,
    val savedSettings: DownloadSettings,
    val appSettings: AppSettings,
    val savedAppSettings: AppSettings,
    val enabledHandlerOverrides: Map<String, Boolean>,
    val alwaysAskOverride: Boolean?,
    val installedHandlerIds: Set<String>,
    val themeIds: Set<String>,
    val selectedThemeOverride: String?,
)

/**
 * Everything the Settings screen does against the data layer, bundled behind one collaborator so
 * [SettingsScreenModel] holds a single dependency instead of nine repositories and registries. It owns
 * the hot snapshot streams, the plugin-registry mutations, and the three cross-cutting draft effects -
 * pushing a draft to live in-memory state ([applyLive]), committing it to disk ([persist]), and
 * rewinding an uncommitted draft when the screen is abandoned ([revertToPersisted]).
 *
 * It is deliberately screen-local (not under `domain.usecase`) because it speaks in presentation types
 * ([HandlersSnapshot], [ThemeUiState]); the operations it wraps stay exactly as the screen model
 * performed them before the extraction.
 */
class SettingsUseCases(
    private val settingsRepository: EngineSettingsRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val autoStartCoordinator: AutoStartCoordinator,
    private val trayService: TrayService,
    private val notificationService: NotificationService,
    private val resolverRegistry: LinkResolverRegistry,
    private val resolverSettingsRepository: LinkResolverSettingsRepository,
    private val themeRegistry: ThemeRegistry,
    private val themeSettingsRepository: ThemeSettingsRepository,
) {

    /** The persisted values a freshly-opened Settings screen seeds its draft from. */
    fun initialValues(): Pair<DownloadSettings, AppSettings> =
        settingsRepository.settings.value to appSettingsRepository.settings.value

    fun supportFlags(): SettingsSupportFlags = SettingsSupportFlags(
        traySupported = trayService.isSupported,
        autoStartSupported = autoStartCoordinator.isSupported,
        notificationsSupported = notificationService.isSupported,
    )

    /** The handler list joined with resolver settings, so the sidebar and rows reflect enable/always-ask. */
    fun observeHandlers(): Flow<HandlersSnapshot> =
        combine(resolverRegistry.handlers, resolverSettingsRepository.settings) { handlers, settings ->
            HandlersSnapshot(
                handlers = handlers.map { loaded ->
                    HandlerUiState(
                        id = loaded.resolver.id,
                        displayName = loaded.resolver.displayName,
                        description = loaded.resolver.description,
                        enabled = loaded.resolver.id !in settings.disabledHandlers,
                        isPlugin = loaded.source == ResolverSource.PLUGIN,
                        resolver = loaded
                    )
                },
                alwaysAsk = settings.alwaysAskHandler,
                extensionDir = settings.extensionDir
            )
        }

    /** The theme list joined with the selected id and theme settings. */
    fun observeThemes(): Flow<ThemesSnapshot> =
        combine(
            themeRegistry.themes,
            themeRegistry.selectedThemeId,
            themeSettingsRepository.settings
        ) { themes, selectedId, settings ->
            ThemesSnapshot(
                themes = themes.map { loaded ->
                    ThemeUiState(
                        id = loaded.provider.id,
                        displayName = loaded.provider.displayName,
                        description = loaded.provider.description,
                        active = loaded.provider.id == selectedId,
                        isPlugin = loaded.source == ThemeSource.PLUGIN
                    )
                },
                themesDir = settings.extensionDir
            )
        }

    // ---- plugin registry mutations ----

    suspend fun installHandler(jarPath: String) {
        resolverRegistry.install(Path.of(jarPath))
    }

    suspend fun uninstallHandler(id: String) {
        resolverRegistry.uninstall(id)
    }

    fun reloadHandlers() {
        resolverRegistry.reload()
    }

    suspend fun installTheme(jarPath: String) {
        themeRegistry.install(Path.of(jarPath))
    }

    suspend fun uninstallTheme(id: String) {
        themeRegistry.uninstall(id)
    }

    fun reloadThemes() {
        themeRegistry.reload()
    }

    // ---- draft effects ----

    /**
     * Push the buffered draft into the repositories' / registries' live in-memory state so the running
     * app reflects it (restyle, logging level, limits...), WITHOUT writing to disk. Dirty state is left
     * intact on purpose: nothing has been persisted yet, so OK is still needed.
     */
    fun applyLive(draft: SettingsDraft) {
        // Appearance mode, logging, download limits, ... (whole draft at once).
        settingsRepository.updateSettingsInMemory { draft.settings }

        // Tray follows the in-memory app settings live; autostart has no in-memory-only
        // effect, so "live" here means actually writing/removing the OS registration.
        appSettingsRepository.updateSettingsInMemory { draft.appSettings }
        autoStartCoordinator.applyLive(draft.appSettings.runAtStartup)

        // Link-handler enable/disable and always-ask.
        if (draft.enabledHandlerOverrides.isNotEmpty() || draft.alwaysAskOverride != null) {
            resolverSettingsRepository.updateSettingsInMemory { settings ->
                val disabled = settings.disabledHandlers.toMutableSet()
                draft.enabledHandlerOverrides.forEach { (id, enabled) ->
                    if (enabled) disabled.remove(id) else disabled.add(id)
                }
                settings.copy(
                    disabledHandlers = disabled.toList(),
                    alwaysAskHandler = draft.alwaysAskOverride ?: settings.alwaysAskHandler
                )
            }
        }

        // Color theme selection.
        draft.selectedThemeOverride
            ?.takeIf { it in draft.themeIds }
            ?.let { themeRegistry.selectInMemory(it) }
    }

    /**
     * Write the buffered draft to disk - the only place persistence happens - replaying handler and theme
     * overrides only for plugins that are still installed (a removed one has no target).
     */
    suspend fun persist(draft: SettingsDraft) {
        if (draft.settings != draft.savedSettings) {
            settingsRepository.updateSettings { draft.settings }
        }
        if (draft.appSettings != draft.savedAppSettings) {
            appSettingsRepository.updateSettings { draft.appSettings }
        }
        // Make sure the OS registration matches what was just persisted, even if the
        // user hit OK without Apply first (revertToPersisted restores the persisted value).
        autoStartCoordinator.applyLive(draft.appSettings.runAtStartup)
        // Only replay overrides for handlers still installed (a removed one has no target).
        draft.enabledHandlerOverrides.forEach { (id, enabled) ->
            if (id in draft.installedHandlerIds) resolverRegistry.setEnabled(id, enabled)
        }
        draft.alwaysAskOverride?.let { resolverRegistry.setAlwaysAsk(it) }
        draft.selectedThemeOverride
            ?.takeIf { it in draft.themeIds }
            ?.let { themeRegistry.select(it) }
    }

    /**
     * Revert the live in-memory state back to the settings actually stored on disk. This runs for Cancel
     * and for navigating away; after OK it is a no-op because OK already persisted the new values, so
     * reverting restores exactly what was just saved.
     */
    fun revertToPersisted() {
        settingsRepository.revertToPersisted()
        appSettingsRepository.revertToPersisted()
        autoStartCoordinator.revertToPersisted()
        resolverSettingsRepository.revertToPersisted()
        themeRegistry.restoreSelectionFromSettings()
    }
}
