package org.blaze.i18n

interface BlazeStrings {
    val common: Common
    val downloads: Downloads
    val settings: Settings
    val errors: Errors
    val filePicker: FilePicker
    val statusSummary: StatusSummary
    val tray: Tray

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

        val selection: Selection
        interface Selection {
            /** Tooltip/label for the per-row selection checkbox. */
            val select: String
            /** Toolbar action that selects every row currently in view. */
            val selectAll: String
            /** Toolbar action that clears the current selection. */
            val clearSelection: String
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
            val sourceWarning: String
            val files: String
            val selectAll: String
            val deselectAll: String
            val itemsSelected: String
            val batchTitle: String
            val scheduleDelayLabel: String
            val scheduleDelayPlaceholder: String

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

            /** Inline notice in the add-download review step. */
            val destinationNotWritable: String
            fun notEnoughSpace(needed: String, free: String): String
            /** The probe already proved a pause would cost the whole transfer. */
            val sourceCannotResume: String
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
             * fetched can't be continued from - whether the server refused a resume or the data on
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
    
    interface Settings {
        val title: String
        val placeholder: String
        val generalCategory: String
        val downloadsCategory: String
        val filesCategory: String
        val logsCategory: String
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
        val clipboardMonitoringLabel: String
        val clipboardMonitoringDesc: String
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
}
