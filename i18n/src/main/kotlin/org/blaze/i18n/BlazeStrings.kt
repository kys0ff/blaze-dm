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
        val searchPlaceholder: String
        val clearSearch: String
        
        val toolbar: Toolbar
        interface Toolbar {
            val add: String
            val resumeAll: String
            val pauseAll: String
            val clearCompleted: String
            val importPortable: String
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
            }
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
    }

    /** Labels for the system-tray context menu (rendered by AWT, not Compose). */
    interface Tray {
        val show: String
        val hide: String
        val pauseAll: String
        val resumeAll: String
        val quit: String
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
        val appearanceHeader: String
        val themeLabel: String
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
