package org.blaze.presentation.screens.settings

import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.EnStrings
import org.blaze.presentation.screens.settings.state.SettingsCategory

/**
 * One searchable Settings option: the single source of truth for the row's title and description, the
 * group (section header) it lives under, and the page (category) that hosts it.
 *
 * The Settings sections render their labels through the registry and the search index is built from
 * the very same [title] / [description] accessors, so the picture the user sees and the text they can
 * search can never drift apart. [keywords] are the aliases a download-manager user reaches for that
 * the literal label does not contain ("proxy" for the network group, "tray" for system integration),
 * matched alongside English and current-language titles.
 */
data class SettingsOption(
    val id: String,
    val category: SettingsCategory,
    val groupKey: String,
    val title: (BlazeStrings) -> String,
    val description: ((BlazeStrings) -> String)? = null,
    val keywords: List<String> = emptyList(),
)

/**
 * The registry of every static Settings option and its section header. Dynamic enumerations (the list
 * of installed link handlers, the list of installed themes) are driven by their own on-disk registries
 * and are deliberately not listed here — only the *preferences* a user searches for are.
 *
 * Ids are stable, dotted and namespaced by category; a `SettingRow` asks for one and resolves its
 * strings here, so a typo fails loudly rather than silently dropping a row from the search index.
 */
object SettingsRegistry {

    /** Section (group-header) titles, keyed by [SettingsOption.groupKey]. */
    private val groups: Map<String, (BlazeStrings) -> String> = mapOf(
        "appearance" to { it.settings.appearanceHeader },
        "typography" to { it.settings.typographyHeader },
        "systemIntegration" to { it.settings.systemIntegrationHeader },
        "security" to { it.settings.securityHeader },
        "startup" to { it.settings.startupHeader },
        "concurrency" to { it.settings.concurrencyHeader },
        "bandwidth" to { it.settings.bandwidthHeader },
        "retry" to { it.settings.retryHeader },
        "network" to { it.settings.networkHeader },
        "torrent" to { it.settings.torrentHeader },
        "destination" to { it.settings.destinationHeader },
        "fileConflicts" to { it.settings.fileConflictsHeader },
        "handlers.sites" to { it.settings.handlers.supportedSitesHeader },
        "handlers.dir" to { it.settings.handlers.extensionsDirHeader },
        "themes.color" to { it.settings.themes.colorThemeHeader },
        "themes.chrome" to { it.settings.themes.chromeHeader },
        "themes.dir" to { it.settings.themes.themesDirHeader },
        "notifications.main" to { it.settings.notifications.header },
        "notifications.attention" to { it.settings.notifications.attentionHeader },
        "shortcuts" to { it.settings.shortcuts.header },
        "logging.main" to { it.settings.logging.header },
        "logging.files" to { it.settings.logging.filesHeader },
    )

    val options: List<SettingsOption> = listOf(
        // ── General ──────────────────────────────────────────────────────────
        SettingsOption(
            id = "general.language", category = SettingsCategory.GENERAL, groupKey = "appearance",
            title = { it.settings.languageLabel }, keywords = listOf("locale", "arabic", "english", "language"),
        ),
        SettingsOption(
            id = "general.theme", category = SettingsCategory.GENERAL, groupKey = "appearance",
            title = { it.settings.themeLabel }, keywords = listOf("dark", "light", "appearance", "mode", "follow system"),
        ),
        SettingsOption(
            id = "general.uiFont", category = SettingsCategory.GENERAL, groupKey = "typography",
            title = { it.settings.uiFontLabel }, description = { it.settings.uiFontDesc },
            keywords = listOf("font", "typeface", "family", "inter", "jetbrains", "mono", "text"),
        ),
        SettingsOption(
            id = "general.monoFont", category = SettingsCategory.GENERAL, groupKey = "typography",
            title = { it.settings.monoFontLabel }, description = { it.settings.monoFontDesc },
            keywords = listOf("font", "monospace", "fixed width", "jetbrains mono", "code", "console", "logs"),
        ),
        SettingsOption(
            id = "general.uiTextScale", category = SettingsCategory.GENERAL, groupKey = "typography",
            title = { it.settings.uiTextScaleLabel }, description = { it.settings.uiTextScaleDesc },
            keywords = listOf("font size", "text size", "zoom", "scale", "bigger", "smaller", "percent"),
        ),
        SettingsOption(
            id = "general.trayEnabled", category = SettingsCategory.GENERAL, groupKey = "systemIntegration",
            title = { it.settings.trayEnabledLabel }, description = { it.settings.trayEnabledDesc },
            keywords = listOf("tray", "system tray", "background", "icon"),
        ),
        SettingsOption(
            id = "general.minimizeToTray", category = SettingsCategory.GENERAL, groupKey = "systemIntegration",
            title = { it.settings.minimizeToTrayLabel }, keywords = listOf("tray", "close", "hide", "minimize"),
        ),
        SettingsOption(
            id = "general.runAtStartup", category = SettingsCategory.GENERAL, groupKey = "systemIntegration",
            title = { it.settings.runAtStartupLabel }, description = { it.settings.runAtStartupDesc },
            keywords = listOf("autostart", "startup", "boot", "login"),
        ),
        SettingsOption(
            id = "general.startMinimizedInTray", category = SettingsCategory.GENERAL, groupKey = "systemIntegration",
            title = { it.settings.startMinimizedInTrayLabel }, description = { it.settings.startMinimizedInTrayDesc },
            keywords = listOf("minimize", "minimised", "tray", "startup", "hidden", "autostart"),
        ),
        SettingsOption(
            id = "general.clipboardMonitoring", category = SettingsCategory.GENERAL, groupKey = "systemIntegration",
            title = { it.settings.clipboardMonitoringLabel }, description = { it.settings.clipboardMonitoringDesc },
            keywords = listOf("clipboard", "paste", "link", "magnet"),
        ),
        SettingsOption(
            id = "general.security.approveExternalClients", category = SettingsCategory.GENERAL, groupKey = "security",
            title = { it.settings.approveExternalClientsLabel }, description = { it.settings.approveExternalClientsDesc },
            keywords = listOf("security", "consent", "api", "local api", "third party", "client", "external"),
        ),
        SettingsOption(
            id = "general.security.rememberedClients", category = SettingsCategory.GENERAL, groupKey = "security",
            title = { it.settings.rememberedClientsLabel }, description = { it.settings.rememberedClientsEmpty },
            keywords = listOf("security", "approval", "allowed apps", "revoke"),
        ),
        SettingsOption(
            id = "general.security.approveExtensions", category = SettingsCategory.GENERAL, groupKey = "security",
            title = { it.settings.approveExtensionsLabel }, description = { it.settings.approveExtensionsDesc },
            keywords = listOf("security", "consent", "extension", "plugin", "jar", "theme", "link handler"),
        ),
        SettingsOption(
            id = "general.security.trustedExtensions", category = SettingsCategory.GENERAL, groupKey = "security",
            title = { it.settings.trustedExtensionsLabel }, description = { it.settings.trustedExtensionsEmpty },
            keywords = listOf("security", "approval", "trusted", "extension", "forget"),
        ),
        SettingsOption(
            id = "general.resumeOnStartup", category = SettingsCategory.GENERAL, groupKey = "startup",
            title = { it.settings.resumeOnStartupLabel }, keywords = listOf("resume", "startup", "continue"),
        ),
        SettingsOption(
            id = "general.startQueuedOnStartup", category = SettingsCategory.GENERAL, groupKey = "startup",
            title = { it.settings.startQueuedOnStartupLabel }, keywords = listOf("queue", "start", "startup"),
        ),

        // ── Downloads ────────────────────────────────────────────────────────
        SettingsOption(
            id = "downloads.maxConcurrentDownloads", category = SettingsCategory.DOWNLOADS, groupKey = "concurrency",
            title = { it.settings.maxConcurrentDownloadsLabel }, description = { it.settings.maxConcurrentDownloadsDesc },
            keywords = listOf("concurrency", "parallel", "simultaneous", "queue"),
        ),
        SettingsOption(
            id = "downloads.maxConnections", category = SettingsCategory.DOWNLOADS, groupKey = "concurrency",
            title = { it.settings.maxConnectionsLabel }, description = { it.settings.maxConnectionsDesc },
            keywords = listOf("connections", "segments", "threads", "split"),
        ),
        SettingsOption(
            id = "downloads.defaultPriority", category = SettingsCategory.DOWNLOADS, groupKey = "concurrency",
            title = { it.settings.defaultPriorityLabel }, description = { it.settings.defaultPriorityDesc },
            keywords = listOf("priority", "queue", "urgent"),
        ),
        SettingsOption(
            id = "downloads.speedLimitEnabled", category = SettingsCategory.DOWNLOADS, groupKey = "bandwidth",
            title = { it.settings.speedLimitEnabledLabel }, keywords = listOf("bandwidth", "throttle", "limit", "speed"),
        ),
        SettingsOption(
            id = "downloads.speedLimit", category = SettingsCategory.DOWNLOADS, groupKey = "bandwidth",
            title = { it.settings.speedLimitLabel }, keywords = listOf("bandwidth", "kbps", "limit", "rate"),
        ),
        SettingsOption(
            id = "downloads.snailModeEnabled", category = SettingsCategory.DOWNLOADS, groupKey = "bandwidth",
            title = { it.settings.snailModeEnabledLabel }, keywords = listOf("snail", "throttle", "slow", "crawl"),
        ),
        SettingsOption(
            id = "downloads.snailSpeed", category = SettingsCategory.DOWNLOADS, groupKey = "bandwidth",
            title = { it.settings.snailSpeedLabel }, description = { it.settings.snailSpeedDesc },
            keywords = listOf("snail", "speed", "kbps"),
        ),
        SettingsOption(
            id = "downloads.autoRetry", category = SettingsCategory.DOWNLOADS, groupKey = "retry",
            title = { it.settings.autoRetryLabel }, keywords = listOf("retry", "failed", "automatic"),
        ),
        SettingsOption(
            id = "downloads.maxRetries", category = SettingsCategory.DOWNLOADS, groupKey = "retry",
            title = { it.settings.maxRetriesLabel }, keywords = listOf("retry", "attempts"),
        ),
        SettingsOption(
            id = "downloads.retryDelay", category = SettingsCategory.DOWNLOADS, groupKey = "retry",
            title = { it.settings.retryDelayLabel }, keywords = listOf("retry", "delay", "wait"),
        ),
        SettingsOption(
            id = "downloads.exponentialBackoff", category = SettingsCategory.DOWNLOADS, groupKey = "retry",
            title = { it.settings.exponentialBackoffLabel }, keywords = listOf("backoff", "retry", "delay"),
        ),
        SettingsOption(
            id = "downloads.userAgent", category = SettingsCategory.DOWNLOADS, groupKey = "network",
            title = { it.settings.userAgentLabel }, description = { it.settings.userAgentDesc },
            keywords = listOf("user agent", "network", "http", "header", "proxy", "browser"),
        ),
        SettingsOption(
            id = "downloads.maxRedirects", category = SettingsCategory.DOWNLOADS, groupKey = "network",
            title = { it.settings.maxRedirectsLabel }, description = { it.settings.maxRedirectsDesc },
            keywords = listOf("redirect", "network", "http"),
        ),
        SettingsOption(
            id = "downloads.maxPeerConnections", category = SettingsCategory.DOWNLOADS, groupKey = "torrent",
            title = { it.settings.maxPeerConnectionsLabel }, description = { it.settings.maxPeerConnectionsDesc },
            keywords = listOf("torrent", "peer", "connections", "swarm"),
        ),
        SettingsOption(
            id = "downloads.seedingEnabled", category = SettingsCategory.DOWNLOADS, groupKey = "torrent",
            title = { it.settings.seedingEnabledLabel }, keywords = listOf("seed", "share", "torrent", "upload"),
        ),
        SettingsOption(
            id = "downloads.unlimitedSeeding", category = SettingsCategory.DOWNLOADS, groupKey = "torrent",
            title = { it.settings.unlimitedSeedingLabel }, keywords = listOf("seed", "torrent", "indefinite"),
        ),
        SettingsOption(
            id = "downloads.seedTimeLimit", category = SettingsCategory.DOWNLOADS, groupKey = "torrent",
            title = { it.settings.seedTimeLimitLabel }, keywords = listOf("seed", "torrent", "minutes"),
        ),
        SettingsOption(
            id = "downloads.seedRatioLimit", category = SettingsCategory.DOWNLOADS, groupKey = "torrent",
            title = { it.settings.seedRatioLimitLabel }, description = { it.settings.seedRatioLimitDesc },
            keywords = listOf("seed", "ratio", "torrent", "share"),
        ),
        SettingsOption(
            id = "downloads.torrentFilePreviewEnabled", category = SettingsCategory.DOWNLOADS, groupKey = "torrent",
            title = { it.settings.torrentFilePreviewLabel }, description = { it.settings.torrentFilePreviewDesc },
            keywords = listOf("torrent", "preview", ".torrent", "metainfo", "add download", "files"),
        ),

        // ── Files ────────────────────────────────────────────────────────────
        SettingsOption(
            id = "files.defaultDownloadDir", category = SettingsCategory.FILES, groupKey = "destination",
            title = { it.settings.defaultDownloadDirLabel }, keywords = listOf("destination", "save", "folder", "directory"),
        ),
        SettingsOption(
            id = "files.askWhereToSave", category = SettingsCategory.FILES, groupKey = "destination",
            title = { it.settings.askWhereToSaveLabel }, keywords = listOf("destination", "save", "prompt", "ask"),
        ),
        SettingsOption(
            id = "files.conflictBehavior", category = SettingsCategory.FILES, groupKey = "fileConflicts",
            title = { it.settings.fileConflictBehaviorLabel }, keywords = listOf("conflict", "overwrite", "rename", "skip", "exists"),
        ),

        // ── Link Handlers ─────────────────────────────────────────────────────
        SettingsOption(
            id = "handlers.alwaysAsk", category = SettingsCategory.EXTENSIONS, groupKey = "handlers.sites",
            title = { it.settings.handlers.alwaysAskLabel }, description = { it.settings.handlers.alwaysAskDesc },
            keywords = listOf("handler", "resolver", "link", "mediafire", "plugin"),
        ),
        SettingsOption(
            id = "handlers.extensionsDir", category = SettingsCategory.EXTENSIONS, groupKey = "handlers.dir",
            title = { it.settings.handlers.extensionsDirLabel }, description = { it.settings.handlers.extensionsDirDesc },
            keywords = listOf("extension", "plugin", "jar", "folder", "install"),
        ),

        // ── Themes ────────────────────────────────────────────────────────────
        SettingsOption(
            id = "themes.colorTheme", category = SettingsCategory.THEMES, groupKey = "themes.color",
            title = { it.settings.themes.colorThemeHeader }, description = { it.settings.themes.colorThemeDesc },
            keywords = listOf("accent", "appearance", "skin", "dark", "light"),
        ),
        SettingsOption(
            id = "themes.ambientGlow", category = SettingsCategory.THEMES, groupKey = "themes.chrome",
            title = { it.settings.themes.ambientGlowLabel }, description = { it.settings.themes.ambientGlowDesc },
            keywords = listOf("glow", "title bar", "chrome", "frame", "accent"),
        ),
        SettingsOption(
            id = "themes.dir", category = SettingsCategory.THEMES, groupKey = "themes.dir",
            title = { it.settings.themes.themesDirLabel }, description = { it.settings.themes.themesDirDesc },
            keywords = listOf("extension", "plugin", "jar", "folder", "install"),
        ),

        // ── Notifications ─────────────────────────────────────────────────────
        SettingsOption(
            id = "notifications.enabled", category = SettingsCategory.NOTIFICATIONS, groupKey = "notifications.main",
            title = { it.settings.notifications.enabledLabel }, description = { it.settings.notifications.enabledDesc },
            keywords = listOf("desktop", "toast", "alert", "notify"),
        ),
        SettingsOption(
            id = "notifications.completed", category = SettingsCategory.NOTIFICATIONS, groupKey = "notifications.main",
            title = { it.settings.notifications.completedLabel }, keywords = listOf("notify", "complete", "finish"),
        ),
        SettingsOption(
            id = "notifications.failed", category = SettingsCategory.NOTIFICATIONS, groupKey = "notifications.main",
            title = { it.settings.notifications.failedLabel }, keywords = listOf("notify", "fail", "error"),
        ),
        SettingsOption(
            id = "notifications.restarted", category = SettingsCategory.NOTIFICATIONS, groupKey = "notifications.main",
            title = { it.settings.notifications.restartedLabel }, keywords = listOf("notify", "restart", "resume"),
        ),
        SettingsOption(
            id = "notifications.whileInSight", category = SettingsCategory.NOTIFICATIONS, groupKey = "notifications.attention",
            title = { it.settings.notifications.whileInSightLabel }, description = { it.settings.notifications.whileInSightDesc },
            keywords = listOf("focus", "visible", "background", "notify"),
        ),

        // ── Keyboard shortcuts ────────────────────────────────────────────────
        // One row per bindable action so Search Everywhere and the in-Settings filter surface a rebind the
        // same way they surface any other preference; the ids mirror the BlazeKeymap action names.
        SettingsOption(
            id = "shortcuts.NewDownload", category = SettingsCategory.SHORTCUTS, groupKey = "shortcuts",
            title = { it.settings.shortcuts.newDownload },
            keywords = listOf("keybinding", "hotkey", "accelerator", "new", "download"),
        ),
        SettingsOption(
            id = "shortcuts.SearchEverywhere", category = SettingsCategory.SHORTCUTS, groupKey = "shortcuts",
            title = { it.settings.shortcuts.searchEverywhere },
            keywords = listOf("keybinding", "hotkey", "accelerator", "search"),
        ),
        SettingsOption(
            id = "shortcuts.FocusListSearch", category = SettingsCategory.SHORTCUTS, groupKey = "shortcuts",
            title = { it.settings.shortcuts.focusListSearch },
            keywords = listOf("keybinding", "hotkey", "accelerator", "find", "filter"),
        ),
        SettingsOption(
            id = "shortcuts.ToggleDetails", category = SettingsCategory.SHORTCUTS, groupKey = "shortcuts",
            title = { it.settings.shortcuts.toggleDetails },
            keywords = listOf("keybinding", "hotkey", "accelerator", "details", "pane"),
        ),
        SettingsOption(
            id = "shortcuts.SelectAll", category = SettingsCategory.SHORTCUTS, groupKey = "shortcuts",
            title = { it.settings.shortcuts.selectAll },
            keywords = listOf("keybinding", "hotkey", "accelerator", "selection"),
        ),
        SettingsOption(
            id = "shortcuts.TogglePause", category = SettingsCategory.SHORTCUTS, groupKey = "shortcuts",
            title = { it.settings.shortcuts.togglePause },
            keywords = listOf("keybinding", "hotkey", "accelerator", "pause", "resume"),
        ),
        SettingsOption(
            id = "shortcuts.Remove", category = SettingsCategory.SHORTCUTS, groupKey = "shortcuts",
            title = { it.settings.shortcuts.remove },
            keywords = listOf("keybinding", "hotkey", "accelerator", "delete"),
        ),
        SettingsOption(
            id = "shortcuts.OpenSelected", category = SettingsCategory.SHORTCUTS, groupKey = "shortcuts",
            title = { it.settings.shortcuts.openSelected },
            keywords = listOf("keybinding", "hotkey", "accelerator", "open", "file"),
        ),
        SettingsOption(
            id = "shortcuts.OpenSettings", category = SettingsCategory.SHORTCUTS, groupKey = "shortcuts",
            title = { it.settings.shortcuts.openSettings },
            keywords = listOf("keybinding", "hotkey", "accelerator", "preferences"),
        ),

        // ── Logs ──────────────────────────────────────────────────────────────
        SettingsOption(
            id = "logs.level", category = SettingsCategory.LOGS, groupKey = "logging.main",
            title = { it.settings.logging.levelLabel }, description = { it.settings.logging.levelDesc },
            keywords = listOf("verbosity", "debug", "trace", "info", "diagnostic"),
        ),
        SettingsOption(
            id = "logs.fileLogging", category = SettingsCategory.LOGS, groupKey = "logging.main",
            title = { it.settings.logging.fileLoggingLabel }, description = { it.settings.logging.fileLoggingDesc },
            keywords = listOf("log file", "write", "console", "disk"),
        ),
        SettingsOption(
            id = "logs.dir", category = SettingsCategory.LOGS, groupKey = "logging.files",
            title = { it.settings.logging.logDirLabel }, description = { it.settings.logging.logDirDesc },
            keywords = listOf("folder", "directory", "open", "rotated"),
        ),
    )

    private val byIdMap: Map<String, SettingsOption> = options.associateBy { it.id }

    init {
        // A duplicate id would make one row invisible to search while a guard test still saw the
        // other, so it is a hard programming error, caught the moment the registry is touched.
        check(byIdMap.size == options.size) {
            "Duplicate Settings option id in the registry: ${options.groupBy { it.id }.filterValues { it.size > 1 }.keys}"
        }
        // Every option must point at a declared group, or its section header would render blank.
        options.forEach { option ->
            check(groups.containsKey(option.groupKey)) { "Unknown group key '${option.groupKey}' for ${option.id}" }
        }
    }

    /** The option with [id], throwing when it is missing — how a `SettingRow` fails fast on a typo. */
    fun require(id: String): SettingsOption = byIdMap[id]
        ?: throw IllegalArgumentException("Settings option '$id' is not registered (would be invisible to search).")

    fun find(id: String): SettingsOption? = byIdMap[id]

    fun groupTitle(key: String, strings: BlazeStrings): String =
        groups.getValue(key)(strings)

    /** The page label a result's secondary text reads ("General", "Downloads", …). */
    fun categoryTitle(category: SettingsCategory, strings: BlazeStrings): String = when (category) {
        SettingsCategory.GENERAL -> strings.settings.generalCategory
        SettingsCategory.DOWNLOADS -> strings.settings.downloadsCategory
        SettingsCategory.FILES -> strings.settings.filesCategory
        SettingsCategory.EXTENSIONS -> strings.settings.handlers.category
        SettingsCategory.THEMES -> strings.settings.themes.category
        SettingsCategory.NOTIFICATIONS -> strings.settings.notifications.category
        SettingsCategory.SHORTCUTS -> strings.settings.shortcutsCategory
        SettingsCategory.LOGS -> strings.settings.logsCategory
    }

    /** The options living on [category], in registry order. */
    fun optionsFor(category: SettingsCategory): List<SettingsOption> =
        options.filter { it.category == category }

    /** The options under one section header, in registry order (used to decide if a group is empty). */
    fun optionsInGroup(groupKey: String): List<SettingsOption> =
        options.filter { it.groupKey == groupKey }

    /** Whether the category hosts at least one option matching [query] (drives the sidebar prune). */
    fun categoryMatches(category: SettingsCategory, query: String, strings: BlazeStrings): Boolean =
        optionsFor(category).any { matchesQuery(it, query, strings) }

    /** Count of [category]'s options matching [query]; 0 for a blank query (badge suppressed). */
    fun matchCount(category: SettingsCategory, query: String, strings: BlazeStrings): Int =
        if (query.isBlank()) 0 else optionsFor(category).count { matchesQuery(it, query, strings) }

    /**
     * Whether [option] is a hit for [query], searching the current-language title *and* description, the
     * English title/description (so a non-English UI still finds "proxy"), and the curated keywords —
     * the exact same fields the Search Everywhere Settings tab indexes, so the two never disagree.
     */
    fun matchesQuery(option: SettingsOption, query: String, strings: BlazeStrings): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        val haystack = ArrayList<String>(4 + option.keywords.size)
        haystack += option.title(strings)
        option.description?.let { haystack += it(strings) }
        if (strings !== EnStrings) {
            haystack += option.title(EnStrings)
            option.description?.let { haystack += it(EnStrings) }
        }
        haystack += option.keywords
        return haystack.any { it.contains(q, ignoreCase = true) }
    }
}
