package org.blaze.i18n

interface BlazeStrings {
    val common: Common
    val downloads: Downloads
    val settings: Settings
    val errors: Errors
    val filePicker: FilePicker
    val statusSummary: StatusSummary
    val tray: Tray
    val menu: Menu
    val search: Search
    val extensions: Extensions
    val consent: Consent
    val options: Options
    val serverLog: ServerLog

    interface Common {
        val ok: String
        val apply: String
        val cancel: String
        val browse: String
        val path: String
        val name: String
        val home: String
        val newFolder: String
        val refresh: String
        val unknown: String
        val copyDetails: String
        val close: String
        val switchLight: String
        val switchDark: String
        val views: String
        val connected: String
        val offline: String
        /** Content description for a link-handler / extension icon. */
        val extensionIcon: String
    }

    interface Downloads {
        val title: String
        val emptyTitle: String
        val emptyDescription: String
        val emptyAction: String
        /** Empty-state shortcut that opens the add dialog with the clipboard contents pre-filled. */
        val emptyPaste: String
        /** Empty-state hint that a torrent, magnet or link can be dropped straight onto the window. */
        val emptyDropHint: String

        /** Feedback for the whole-window drag-and-drop surface. */
        val drop: Drop
        interface Drop {
            /** Headline of the overlay while a usable drag hovers the window. */
            val overlayTitle: String
            /** Headline of the overlay when the dragged payload can't become a download. */
            val overlayUnsupportedTitle: String
            val hintTorrents: String
            val hintLinks: String
            val hintLinkLists: String
            val hintPartials: String
            /** Suffix on the hovered-items preview, e.g. "+3 more". */
            fun itemsMore(count: Int): String
            /** Balloon after a successful drop: the sources await review in the add dialog. */
            fun queued(count: Int): String
            /** Balloon naming the dropped values that could not be used. */
            fun rejected(names: String): String
            /** Balloon when nothing in the drop was usable at all. */
            val nothingUsable: String
        }
        val searchPlaceholder: String
        val clearSearch: String

        /** Labels for the downloads-list state filter chips. */
        val filters: Filters
        interface Filters {
            val all: String
            val active: String
            val queued: String
            val paused: String
            val completed: String
            val failed: String
            val seeding: String
        }

        /** Labels for the downloads-list sort control. */
        val sorting: Sorting
        interface Sorting {
            val name: String
            val size: String
            val speed: String
            val state: String
            val dateAdded: String
            val ascending: String
            val descending: String
        }

        /** Header labels for the download table's columns (sorting reuses [Sorting]). */
        val columns: Columns
        interface Columns {
            val name: String
            val progress: String
            val size: String
            val speed: String
            val eta: String
            val status: String
        }

        val selection: Selection
        interface Selection {
            /** Tooltip/label for the per-row selection checkbox. */
            val select: String
            /** Toolbar action that selects every row currently in view. */
            val selectAll: String
            /** Toolbar action that clears the current selection. */
            val clearSelection: String
            /** The same toolbar button once everything is selected: it now empties the selection. */
            val deselect: String
            /** Prefix for the bulk-action bar, e.g. "3 selected". */
            fun count(n: Int): String
            /** Bulk action that pauses every selected transfer. */
            val pause: String
            /** Bulk action that resumes every selected transfer. */
            val resume: String
            /** Bulk action that cancels every selected transfer. */
            val cancel: String
            /** Bulk action that removes every selected row. */
            val remove: String
            /** Count-aware bulk tooltips so a toolbar button reads "Pause 3 downloads" (requirement). */
            fun pauseSelected(n: Int): String
            fun resumeSelected(n: Int): String
            fun cancelSelected(n: Int): String
            fun removeSelected(n: Int): String
            /** Title of the bulk-remove confirmation dialog. */
            val bulkRemoveTitle: String
            /** Body of the bulk-remove confirmation, naming how many rows leave the list. */
            fun bulkRemoveMessage(n: Int): String
        }
        
        /** Labels for the collapsible Details tool window shown beside the list. */
        val detailsPane: DetailsPane
        interface DetailsPane {
            val tabGeneral: String
            val tabFiles: String
            /** Shown in the pane when no single download is selected. */
            val emptyHint: String
            /** Toolbar/stripe toggle that hides the pane. */
            val hide: String
            /** Toolbar/stripe toggle that brings the pane back. */
            val show: String

            /** The gear menu that opens the pane's IDE-style tool-window options. */
            val options: ToolWindowOptions
            interface ToolWindowOptions {
                /** Tooltip for the gear button that opens the options menu. */
                val menuTooltip: String
                /** Toggle that stacks the pane's tabs into one continuous view. */
                val groupTabs: String
                val viewMode: String
                val moveTo: String
                val resize: String
                // View Mode (5)
                val dockPinned: String
                val dockUnpinned: String
                val undock: String
                val float: String
                val window: String
                // Move To (6)
                val leftTop: String
                val leftBottom: String
                val rightTop: String
                val rightBottom: String
                val bottomLeft: String
                val bottomRight: String
                // Resize (5)
                val stretchLeft: String
                val stretchRight: String
                val stretchTop: String
                val stretchBottom: String
                val maximize: String
            }
        }

        val toolbar: Toolbar
        interface Toolbar {
            val add: String
            val resumeAll: String
            val pauseAll: String
            val clearCompleted: String
            val importPortable: String
            /** Tooltip for the Snail Mode toolbar toggle. */
            val snailMode: String
        }

        /**
         * The download table's "more" menu, sitting at the end of the column header — the IDE's table
         * options, not a settings gear.
         */
        val tableOptions: TableOptions
        interface TableOptions {
            /** Tooltip of the vertical-ellipsis button that opens the menu. */
            val menuTooltip: String
            /** Submenu that shows or hides individual columns. */
            val columns: String
            /** Restores every column to its default width. */
            val resetColumnWidths: String
        }

        /** Labels for the status-bar Background Tasks popup. */
        val backgroundTasks: BackgroundTasks
        interface BackgroundTasks {
            val title: String
            val empty: String
            /** Clickable status-bar summary naming how many transfers are live. */
            fun runningCount(n: Int): String
            /** Popup footer: splits the running set into actively transferring vs still queued. */
            fun footer(active: Int, queued: Int): String
        }

        val dialogs: Dialogs
        interface Dialogs {
            val addTitle: String
            val addUrlPlaceholder: String
            val addFromFile: String
            val selectSourceFile: String
            val addDownload: String
            val addAction: String
            val backToEdit: String
            val downloadSource: String
            val saveTo: String
            val metadataResolved: String
            val url: String
            val size: String
            val fetchingMetadata: String
            val fetchingItems: String
            /** Friendly nudge shown once a fetch runs longer than the plain label implies. */
            val fetchingSlow: String
            /** Reassuring note shown when a fetch looks like it may be struggling. */
            val fetchingTrouble: String
            val sourceWarning: String
            val files: String
            val selectAll: String
            val deselectAll: String
            val itemsSelected: String
            val batchTitle: String
            val scheduleDelayLabel: String
            val scheduleDelayPlaceholder: String

            /**
             * Heading of the optional authentication block in the add-download dialog. The material
             * entered here is ephemeral: it is sent only for this transfer and is never persisted,
             * echoed back or logged.
             */
            val authSectionTitle: String
            /** Label for the bearer-token field. */
            val bearerTokenLabel: String
            val bearerTokenPlaceholder: String
            /** Label for the raw Cookie header field. */
            val cookieLabel: String
            val cookiePlaceholder: String
            /** Explains the lifetime/handling of the credentials just entered. */
            val authHint: String

            /** Tooltip for the paste-from-clipboard action in the add-download source field. */
            val pasteFromClipboard: String
            /** Tooltip/link for clearing the add-download source field. */
            val clearSource: String
            /** Title of the collapsed "Advanced options" disclosure in the add-download dialog. */
            val advancedOptions: String
            /** Detected-source labels shown inline under the source field. */
            val sourceKindMagnet: String
            val sourceKindTorrent: String
            val sourceKindHttp: String
            /** Shown when a picked .txt link list turns out to hold no links. */
            val emptyLinkFile: String
            /** Batch summary, e.g. "3 links". */
            fun linkCount(count: Int): String
            /** Error when the picked source file couldn't be read. */
            fun sourceFileReadFailed(reason: String?): String
            /** Inline destination hints (add-download dialog). */
            fun freeSpace(size: String): String
            val destinationWillCreate: String
            val destinationNotDirectory: String
            val destinationNotWritableHint: String
            val destinationInvalidPath: String
            
            val removeTitle: String
            fun removeMessage(name: String): String
            val removeOnly: String
            val removeOnlyHint: String
            val removeAndDelete: String
            val removeAndDeleteHint: String
            val dontAskAgain: String
            val removeAction: String
            val deleteAction: String

            val conflictTitle: String
            fun conflictMessage(name: String): String
            val overwriteAction: String
            val renameAction: String
            val skipAction: String

            val handlerChoiceTitle: String
            val handlerChoicePrompt: String
            val resolveAction: String
            val resolvingLink: String
            val noHandlerForLink: String
            /** Shown inline under the URL field when exactly one handler matches. */
            fun handlerWillBeUsed(name: String): String
            /** Shown inline under the URL field when several handlers match and picker is on. */
            fun handlerWillAsk(count: Int): String

            // Multi-link resolution: one page can yield several downloadable links, and the
            // user picks which to queue (single -> normal review, several -> batch review).
            /** Title of the pick-the-links dialog. */
            val resolvedLinksTitle: String
            /** Prompt above the checkbox list of resolved links. */
            val resolvedLinksPrompt: String
            /** Confirm label when the pick should start right away. */
            val pickLinksAction: String
            /** Action that queues every resolved link at once. */
            val downloadAllAction: String
            /** Error shown when a handler answered with no downloadable link. */
            val noLinksResolved: String
            /** Footer naming how many of the offered links are selected. */
            fun selectedLinkCount(count: Int, total: Int): String

            /**
             * Shown before saving a page link that no installed handler can take apart. Blaze can
             * still fetch it — the response just isn't the file the user was after — so the outcome
             * is stated and accepted rather than discovered afterward.
             */
            val plainPage: PlainPage
            interface PlainPage {
                val title: String
                fun message(url: String): String
                /** What proceeding really buys: the server's response body, saved as a file. */
                val detail: String
                val confirm: String
            }

            val details: Details
            /** Field labels for the read-only "Download details" dialog. */
            interface Details {
                val title: String
                val name: String
                val status: String
                val location: String
                val source: String
                val size: String
                val speed: String
                val eta: String
                val peers: String
                val added: String
                val scheduled: String
                val files: String
                val error: String
                /** Label for the proven ability to survive a pause. */
                val resumeSupport: String
            }

            /** Shown before pausing a download whose source cannot be continued. */
            val pauseRisk: PauseRisk
            interface PauseRisk {
                val title: String
                fun message(name: String): String
                /** Explains the cost of pausing, in the progress that will be thrown away. */
                fun detail(percent: Int): String
                val confirm: String
            }

            /**
             * Shown before dropping every finished download from the list at once. Clearing is bulk
             * and irreversible for the list (the history, speed and completion time are gone), and it
             * also stops torrents that are still seeding, so the user confirms that scope first.
             */
            val clearCompleted: ClearCompleted
            interface ClearCompleted {
                val title: String
                /** Names how many entries are about to leave the list (completed + seeding). */
                fun message(count: Int): String
                /** Says what clearing does *not* touch, so the decision is an informed one. */
                val detail: String
                /** Warns that finished-but-sharing torrents are stopped by the same action. */
                fun seedingDetail(count: Int): String
                val confirm: String
            }

            /** Inline notice in the add-download review step. */
            val destinationNotWritable: String
            fun notEnoughSpace(needed: String, free: String): String
            /** The probe already proved a pause would cost the whole transfer. */
            val sourceCannotResume: String

            /**
             * Review-step notice when a torrent/magnet's metadata could not be resolved before
             * adding: the download is still queueable, the swarm fills in the details later.
             */
            val metadataUnresolvedTorrent: String
            /** Shown inline when a link probe failed because the server could not be reached. */
            fun httpProbeFailed(url: String): String
        }

        val status: Status
        interface Status {
            val downloading: String
            val paused: String
            val completed: String
            val queued: String
            val error: String
            val cancelling: String
            val seeding: String
            val scheduled: String
        }

        /** Names for the download priority levels, shared by the add dialog, row menu, badge and settings. */
        val priority: Priority
        interface Priority {
            val idle: String
            val low: String
            val normal: String
            val high: String
            /** Title of the priority submenu / selector. */
            val label: String
        }
        
        fun progress(downloaded: String, total: String): String
        fun completedCount(count: Int): String
        fun peers(count: Int): String
        fun speed(speed: String): String
        fun filesCount(count: Int): String

        val actions: Actions
        interface Actions {
            val pause: String
            val resume: String
            val retry: String
            val cancel: String
            val remove: String
            val showFiles: String
            val openFile: String
            val showInFolder: String
            val openSourceLink: String
            val copyLink: String
            val copyFilePath: String
            val showDetails: String
            /** Offered on a finished download whose file has since been deleted from disk. */
            val reDownload: String
            /** Drops a finished download whose file is gone from the list, with nothing left to delete. */
            val clear: String
        }

        /** Labels for the "resume a moved portable download" flow and its on-row indicator. */
        val portable: Portable
        interface Portable {
            /** Unobtrusive badge/tooltip shown while a download is still incomplete. */
            val indicator: String
            val dialogTitle: String
            fun kindHttp(name: String): String
            fun kindTorrent(name: String): String
            val source: String
            val expectedSize: String
            val available: String
            val saveLocation: String
            val resumable: String
            val staleWarning: String
            val credentialsNote: String
            val importAction: String
            fun notPortable(path: String): String
            val imported: String
        }

        /**
         * Copy for the risky-situation notices the downloads list raises: chips on a row, balloon
         * notifications about lifecycle transitions, and the status bar's attention line.
         */
        val advisories: Advisories
        interface Advisories {
            /** Row chip: the source proved it cannot continue an interrupted transfer. */
            val cannotResume: String
            /** Row chip: a torrent with no peers connected and no bytes moving. */
            val waitingForPeers: String
            /** Row chip: a completed download whose file has been deleted or moved out from under the app. */
            val fileMissing: String
            /** Details-dialog answer for a source that can pick a pause back up. */
            val resumeSupported: String
            val titleCompleted: String
            val titleFailed: String
            val titleRestarted: String
            fun completed(name: String): String
            fun failed(name: String, reason: String): String
            /**
             * An unfinished transfer is beginning again from zero, so the bytes it had already
             * fetched can't be continued from - whether the server refused a 'resume' or the data on
             * disk went away. [percent] is how far it had got before starting over.
             */
            fun restarted(name: String, percent: Int): String
            /** Warns that resuming a paused, non-resumable download restarts it from the beginning. */
            fun resumeStartsOver(name: String): String
            /** Warns that pausing several transfers at once costs some of them their progress. */
            fun pauseAllCosts(count: Int): String
            /** Warns that the same destination is already being downloaded. */
            fun duplicateSource(name: String): String
            /** Shown when removing a download that still has progress on disk to lose. */
            fun removeInProgress(percent: Int): String
            /** Status-bar attention text for transfers that cannot survive a pause. */
            fun alertCannotResume(count: Int): String
            /** Status-bar attention text for transfers idle because they found no peers. */
            fun alertWaitingPeers(count: Int): String
        }
    }

    interface FilePicker {
        val titleFolder: String
        val titleFile: String
        val home: String
        val newFolder: String
        val refresh: String
        val showHidden: String
        val hideHidden: String
        val pathPlaceholder: String
        val hintFolder: String
        val hintFile: String
        /** Shown in File mode when the caller lists the pickable types, e.g. "Supported types: .torrent, .txt". */
        fun supportedTypes(extensions: String): String

        val newFolderTitle: String
        val newFolderName: String
        fun newFolderCreatedIn(parent: String): String
        val newFolderError: String

        val errorInvalidPath: String
        val errorPathNotExists: String
        val errorNotAFolder: String
        val errorUnsupportedFileType: String
        val errorInvalidName: String
        val errorIllegalCharacters: String
        val errorAlreadyExists: String

        val collapse: String
        val expand: String
    }

    interface StatusSummary {
        val ready: String
        fun downloading(count: Int): String
        fun queued(count: Int): String
        fun paused(count: Int): String
        fun failed(count: Int): String
        /** Shown while Snail Mode throttles the whole engine; [rate] is a ready-made speed label. */
        fun snailMode(rate: String): String
    }

    /** Labels for the system-tray context menu (rendered by AWT, not Compose). */
    interface Tray {
        val show: String
        val hide: String
        val pauseAll: String
        val resumeAll: String
        val quit: String
        /** Snail Mode menu entry, worded for the action it performs from the current state. */
        val enableSnailMode: String
        val disableSnailMode: String
    }

    /**
     * Labels for the title-bar hamburger main menu and the IDE-style "Speed limit" widget it houses,
     * plus the About dialog. The menu is window chrome (not a screen), so its strings live at the app
     * root rather than under any one screen's group.
     */
    interface Menu {
        /** Tooltip for the hamburger affordance that opens this menu (no accelerator). */
        val mainMenu: String
        /** Tooltip for the title-bar search button, which focuses the list's search field. */
        val search: String
        /** Tooltip/label for the Search Everywhere entry points (title-bar icon, menu, Ctrl/Cmd+K). */
        val searchEverywhere: String
        val newDownload: String
        val settings: String
        val pauseAll: String
        val resumeAll: String
        val toggleDetails: String
        val about: String
        val exit: String
        /** Title-bar main menu headers. */
        val file: String
        val edit: String
        val view: String
        val tools: String
        val help: String
        /** The speed-limit widget's label / tooltip and its two dropdown options. */
        val speedLimit: String
        val fullSpeed: String
        val snailMode: String
        /** About dialog copy. */
        val aboutTitle: String
        val aboutBody: String
        /** Tools-category server entries: opening the log window and the lifecycle submenu. */
        val serverLogs: String
        val server: String
        val startServer: String
        val stopServer: String
        val restartServer: String
        val copyEndpoint: String
        /** Toast after copying the running server's endpoint to the clipboard. */
        val endpointCopied: String
        /** Rows of the window menu the title-bar app icon opens, like the classic system menu. */
        val windowRestore: String
        val windowMinimize: String
        val windowMaximize: String
        val windowClose: String
    }

    /**
     * Copy for the dockable "Server Logs" tool window — the in-process log stream (which carries the
     * local API server's own lines) shown like an IDE Log window, with an IntelliJ-style in-panel find
     * bar. The shared View Mode / Move To / Resize labels are reused from
     * [Downloads.DetailsPane.ToolWindowOptions], so they are not duplicated here.
     */
    interface ServerLog {
        val title: String
        /** Shown in the body when the buffer holds no lines yet. */
        val empty: String
        /** Live server status words shown beside the header controls. */
        val running: String
        val stopped: String
        /** Header quick lifecycle actions and the buffer-clear button. */
        val startAction: String
        val stopAction: String
        val restartAction: String
        val clearAction: String
        /** Tooltip for the hide (tool-window close) affordance. */
        val hide: String
        /** Placeholder + accessible name of the in-panel find field. */
        val findPlaceholder: String
        val clearSearch: String
        /** Tooltip of the find toggle, which spells out its accelerator in the caller. */
        val findTooltip: String
        val nextMatch: String
        val previousMatch: String
        val closeFind: String
        /** Find-bar match counter, e.g. "3 of 12". */
        fun matchCount(current: Int, total: Int): String
        /** Find-bar label when the query matches nothing. */
        val noMatches: String
        /** Header/tooltip label for the endpoint line and its token file. */
        val endpoint: String
        val tokenFile: String
    }
    
    /**
     * Labels for the window-level "Search Everywhere" popup. The popup is chrome, not a screen, so
     * its strings sit at the app root next to [Menu].
     */
    interface Search {
        /** Placeholder of the popup's text field. */
        val placeholder: String
        val tabAll: String
        val tabDownloads: String
        val tabActions: String
        val tabSettings: String
        /** Shown instead of a list when the query is empty and the tab has no meaningful default. */
        val typeToSearch: String
        /** Shown when a non-empty query matches nothing. */
        val nothingFound: String
        /** Section header over the download group in the All tab. */
        val sectionDownloads: String
        /** Section header over the action group in the All tab. */
        val sectionActions: String
        /** Section header over the settings group in the All tab. */
        val sectionSettings: String
    }

    /**
     * Copy for the in-window Extensions tool-window panel (the stripe tab next to Downloads).
     * The panel manages handler lifecycle live; global preferences stay in Settings.
     */
    interface Extensions {
        val title: String
        /** Sub-line under the title explaining what an extension does. */
        val subtitle: String
        val installAction: String
        val reloadAction: String
        val removeAction: String
        val optionsAction: String
        val emptyTitle: String
        val emptyDescription: String
        val builtinTag: String
        val pluginTag: String
        val enabledLabel: String
        /** Tooltip naming the handler that will resolve a link. */
        fun handlesSites(count: Int): String
        /** Confirm-body when removing a plugin jar from disk. */
        fun removeConfirm(name: String): String
        val removeConfirmTitle: String
    }

    interface Settings {
        val title: String
        val placeholder: String
        /** Placeholder of the box that filters the category list, like the IDE Settings search. */
        val searchPlaceholder: String
        /** Accessible name of the search box's clear button. */
        val clearSearch: String
        /** Shown in the category list when the search box filters every entry out. */
        val searchNoMatches: String
        val generalCategory: String
        val downloadsCategory: String
        val filesCategory: String
        val logsCategory: String
        /** Sidebar label of the keyboard-shortcuts page. */
        val shortcutsCategory: String
        val concurrencyHeader: String
        val maxConcurrentDownloadsLabel: String
        val maxConcurrentDownloadsDesc: String
        val maxConnectionsLabel: String
        val maxConnectionsDesc: String
        val defaultPriorityLabel: String
        val defaultPriorityDesc: String
        val bandwidthHeader: String
        val speedLimitEnabledLabel: String
        val speedLimitLabel: String
        val retryHeader: String
        val autoRetryLabel: String
        val maxRetriesLabel: String
        val retryDelayLabel: String
        val exponentialBackoffLabel: String
        val networkHeader: String
        val userAgentLabel: String
        val userAgentDesc: String
        val maxRedirectsLabel: String
        val maxRedirectsDesc: String
        val torrentHeader: String
        val maxPeerConnectionsLabel: String
        val maxPeerConnectionsDesc: String
        val seedingEnabledLabel: String
        val seedTimeLimitLabel: String
        val unlimitedSeedingLabel: String
        val seedRatioLimitLabel: String
        val seedRatioLimitDesc: String
        /** Opt-in: preview a `.torrent` download link's files, then add it as a local torrent. */
        val torrentFilePreviewLabel: String
        val torrentFilePreviewDesc: String
        val snailModeEnabledLabel: String
        val snailSpeedLabel: String
        val snailSpeedDesc: String
        val appearanceHeader: String
        val themeLabel: String
        val languageLabel: String
        val themeSystemOption: String
        val themeLightOption: String
        val themeDarkOption: String
        val startupHeader: String
        val resumeOnStartupLabel: String
        val startQueuedOnStartupLabel: String
        val systemIntegrationHeader: String
        val trayEnabledLabel: String
        val trayEnabledDesc: String
        val minimizeToTrayLabel: String
        val runAtStartupLabel: String
        val runAtStartupDesc: String
        val startMinimizedInTrayLabel: String
        val startMinimizedInTrayDesc: String
        val clipboardMonitoringLabel: String
        val clipboardMonitoringDesc: String
        /** Security group: consent prompts for external API clients and untrusted extension jars. */
        val approveExternalClientsLabel: String
        val approveExternalClientsDesc: String
        val rememberedClientsLabel: String
        val rememberedClientsEmpty: String
        val revokeAccessAction: String
        val approveExtensionsLabel: String
        val approveExtensionsDesc: String
        val trustedExtensionsLabel: String
        val trustedExtensionsEmpty: String
        val forgetAction: String
        /** Kind tag beside a trusted extension ("link handler" / "theme"); the two never share trust. */
        val trustedKindHandler: String
        val trustedKindTheme: String
        val securityHeader: String
        val destinationHeader: String
        val defaultDownloadDirLabel: String
        val askWhereToSaveLabel: String
        val fileConflictsHeader: String
        val fileConflictBehaviorLabel: String
        val askOption: String
        val overwriteOption: String
        val skipOption: String
        val renameOption: String
        val validationPositiveNumber: String
        val validationNonNegativeNumber: String

        val logging: Logging
        interface Logging {
            val header: String
            val levelLabel: String
            val levelDesc: String
            val offOption: String
            val errorOption: String
            val warnOption: String
            val infoOption: String
            val debugOption: String
            val traceOption: String
            val fileLoggingLabel: String
            val fileLoggingDesc: String
            val filesHeader: String
            val logDirLabel: String
            val logDirDesc: String
            val openFolderAction: String
        }

        val handlers: Handlers
        interface Handlers {
            val category: String
            val supportedSitesHeader: String
            val supportedSitesDesc: String
            val alwaysAskLabel: String
            val alwaysAskDesc: String
            val extensionsDirHeader: String
            val extensionsDirLabel: String
            val extensionsDirDesc: String
            val installAction: String
            val reloadAction: String
            val removeAction: String
            val emptyHandlers: String
            val builtinTag: String
            val pluginTag: String
            /** Label of the per-handler gear that opens its option editor. */
            val optionsAction: String
            /** Title of a handler's option dialog, naming the handler. */
            fun optionsTitle(name: String): String
            /** Header of the priority field inside the option dialog. */
            val priorityLabel: String
            val priorityDesc: String
            /** Button that clears every edited option back to its declared default. */
            val restoreDefaultsAction: String
            /** Timeout preference shared by all handlers. */
            val requestTimeoutLabel: String
            val requestTimeoutDesc: String
            /** Balloon shown when an install was refused through the consent dialog. */
            val installRefused: String
            /** Balloon shown when an install failed after approval; [reason] is the underlying error. */
            fun installFailed(reason: String): String
        }

        val themes: Themes
        interface Themes {
            val category: String
            val colorThemeHeader: String
            val colorThemeDesc: String
            val activeRadio: String
            val themesDirHeader: String
            val themesDirLabel: String
            val themesDirDesc: String
            val installAction: String
            val reloadAction: String
            val removeAction: String
            val builtinTag: String
            val pluginTag: String
            /** Label of the per-theme gear that opens its option editor (shown only when it has options). */
            val optionsAction: String
            /** Title of a theme's option dialog, naming the theme. */
            fun optionsTitle(name: String): String
            /** Button that clears every edited theme option back to its declared default. */
            val restoreDefaultsAction: String
            /** Hint under a color field describing the accepted hex format. */
            val colorFormatHint: String
            /**
             * Byline shown under a theme's name, built from its self-declared metadata.
             * Called only when [author] or [version] is non-blank.
             */
            fun themeByline(author: String, version: String): String
            /** Window-chrome group: the IDE's ambient accent glow around the frame's top-left. */
            val chromeHeader: String
            val ambientGlowLabel: String
            val ambientGlowDesc: String
        }

        val notifications: Notifications
        /**
         * Copy for the desktop-notifications category: which download lifecycle events may post a
         * system notice, and whether Blaze may speak while its window is already in sight.
         */
        interface Notifications {
            val category: String
            val header: String
            val enabledLabel: String
            val enabledDesc: String
            val completedLabel: String
            val failedLabel: String
            val restartedLabel: String
            val attentionHeader: String
            val whileInSightLabel: String
            val whileInSightDesc: String
            /** Shown instead of the controls when the desktop offers no notification surface. */
            val unsupportedNote: String
        }

        val shortcuts: Shortcuts
        /**
         * Copy for the keyboard-shortcuts category: the per-action names shown in the list, the recorder's
         * controls, and the validation messages raised when a chord is unsafe or clashes with another action.
         */
        interface Shortcuts {
            val category: String
            val header: String
            val description: String
            /** Action name beside the "New Download" command. */
            val newDownload: String
            val searchEverywhere: String
            val focusListSearch: String
            val toggleDetails: String
            val selectAll: String
            val togglePause: String
            val remove: String
            val openSelected: String
            val openSettings: String
            /** Label of the button that starts capturing a new chord. */
            val recordAction: String
            /** Hint shown while a chord capture is armed. */
            val recordingHint: String
            /** Label of the button that cancels an armed capture. */
            val cancelAction: String
            /** Label of the per-row button that unbinds the action (leaves it with no chord). */
            val clearAction: String
            /** Label of the per-row button that restores just that action to its built-in chord. */
            val resetAction: String
            /** Label of the header button that restores every action to its built-in chord. */
            val restoreAllAction: String
            /** Shown where the chord would print, when the action is unbound. */
            val unboundLabel: String
            /** Error raised when two actions would answer the same chord; names the other action. */
            fun conflictError(otherAction: String): String
            /** Error raised when a recorded chord has no modifier and is not a safe standalone key. */
            val needModifierError: String
        }
    }
    
    interface Errors {
        val networkUnavailable: String
        val timeout: String
        val unauthorized: String
        val notFound: String
        val diskFull: String
        val rangeUnsupported: String
        val invalidTorrent: String
        val metadataTimeout: String
        val cancelled: String
        fun networkFailure(message: String): String
        fun diskError(message: String): String
        fun unknown(message: String): String
    }

    /**
     * Copy shared by the extension option editors (the link-handler and theme dialogs): the live
     * validation messages raised under a field whose current text is not a value the option accepts.
     * Field labels and descriptions come from the plugin's own descriptors, so only the app-owned
     * validation wording lives here.
     */
    interface Options {
        /** A numeric option holds text that is not a whole number. */
        val integerRequired: String
        /** A numeric option is a whole number but outside its declared inclusive range. */
        fun integerRange(min: Long, max: Long): String
        /** A color option holds hex text the parser cannot read. */
        val colorInvalid: String
    }

    /**
     * Copy of the security consent dialogs: the app-wide prompt that pauses an external client or an
     * untrusted extension jar until the user acknowledges what accepting actually means and picks
     * "only this time", "always" or "reject".
     */
    interface Consent {
        /** Title when a third-party program wants to use the local download API. */
        val clientTitle: String
        /** Body naming the requesting client and its stable id. */
        fun clientBody(name: String, id: String): String
        /** The plain statement of what allowing a client permits it to do. */
        val clientMeaning: String
        /** Title before an extension jar would run for the first time. */
        val extensionTitle: String
        /** Body naming the jar about to be installed or loaded. */
        fun extensionBody(fileName: String): String
        /** The plain statement of what allowing an extension permits its code to do. */
        val extensionMeaning: String
        /** Explains the difference between the two allow buttons so the choice is informed. */
        val choiceHelp: String
        /** Points at the Settings page where prompts can be switched off and approvals revoked. */
        val settingsHint: String
        /** Button: allow this occurrence only; ask again after a restart. */
        val allowOnce: String
        /** Button: allow and remember until revoked in Settings. */
        val allowAlways: String
        /** Button: refuse. */
        val deny: String
        /** Label of the row showing the client id / jar hash of the subject. */
        val subjectLabel: String
    }
}
