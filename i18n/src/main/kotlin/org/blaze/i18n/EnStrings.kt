package org.blaze.i18n

object EnStrings : BlazeStrings {
    override val common = object : BlazeStrings.Common {
        override val ok = "OK"
        override val apply = "Apply"
        override val cancel = "Cancel"
        override val browse = "Browse..."
        override val path = "Path"
        override val name = "Name:"
        override val home = "Home"
        override val newFolder = "New folder"
        override val refresh = "Refresh"
        override val unknown = "Unknown"
        override val copyDetails = "Copy details"
        override val close = "Close"
        override val switchLight = "Switch to light theme"
        override val switchDark = "Switch to dark theme"
        override val views = "Views"
        override val connected = "Connected"
        override val offline = "Offline"
        override val extensionIcon = "Extension icon"
    }

    override val downloads = object : BlazeStrings.Downloads {
        override val title = "Downloads"
        override val emptyTitle = "No downloads yet"
        override val emptyDescription = "Add a download to get started"
        override val emptyAction = "Add a download"
        override val emptyPaste = "Paste link from clipboard"
        override val emptyDropHint = "Tip: you can also drop a .torrent file, a magnet link or a URL onto the window."

        override val drop = object : BlazeStrings.Downloads.Drop {
            override val overlayTitle = "Drop to add downloads"
            override val overlayUnsupportedTitle = "Can't drop that here"
            override val hintTorrents = "Torrent files & magnet links"
            override val hintLinks = "Direct download links"
            override val hintLinkLists = "Link lists (.txt — one link per line)"
            override val hintPartials = "Unfinished downloads (.part files & folders)"
            override fun itemsMore(count: Int) = "+$count more"
            override fun queued(count: Int) =
                if (count == 1) "1 source queued — confirm it in the add dialog" else "$count sources queued — confirm them in the add dialog"
            override fun rejected(names: String) = "Can't download: $names"
            override val nothingUsable = "Nothing in that drop can be downloaded"
        }
        override val searchPlaceholder = "Search downloads…"
        override val clearSearch = "Clear search"

        override val filters = object : BlazeStrings.Downloads.Filters {
            override val all = "All"
            override val active = "Active"
            override val queued = "Queued"
            override val paused = "Paused"
            override val completed = "Completed"
            override val failed = "Failed"
            override val seeding = "Seeding"
        }

        override val sorting = object : BlazeStrings.Downloads.Sorting {
            override val name = "Name"
            override val size = "Size"
            override val speed = "Speed"
            override val state = "Status"
            override val dateAdded = "Date added"
            override val ascending = "Ascending"
            override val descending = "Descending"
        }

        override val columns = object : BlazeStrings.Downloads.Columns {
            override val name = "Name"
            override val progress = "Progress"
            override val size = "Size"
            override val speed = "Speed"
            override val eta = "ETA"
            override val status = "Status"
        }

        override val selection = object : BlazeStrings.Downloads.Selection {
            override val select = "Select"
            override val selectAll = "Select all"
            override val clearSelection = "Clear selection"
            override val deselect = "Deselect"
            override fun count(n: Int) = "$n selected"
            override val pause = "Pause"
            override val resume = "Resume"
            override val cancel = "Cancel"
            override val remove = "Remove"
            override fun pauseSelected(n: Int) = "Pause $n ${if (n == 1) "download" else "downloads"}"
            override fun resumeSelected(n: Int) = "Resume $n ${if (n == 1) "download" else "downloads"}"
            override fun cancelSelected(n: Int) = "Cancel $n ${if (n == 1) "download" else "downloads"}"
            override fun removeSelected(n: Int) = "Remove $n ${if (n == 1) "download" else "downloads"}"
            override val bulkRemoveTitle = "Remove downloads"
            override fun bulkRemoveMessage(n: Int) =
                "Remove $n downloads from the list? The downloaded files stay on disk."
        }
        
        override val toolbar = object : BlazeStrings.Downloads.Toolbar {
            override val add = "Add download"
            override val resumeAll = "Resume all"
            override val pauseAll = "Pause all"
            override val clearCompleted = "Clear completed"
            override val importPortable = "Import portable download"
            override val snailMode = "Snail Mode — throttle all downloads to a crawl"
        }

        override val tableOptions = object : BlazeStrings.Downloads.TableOptions {
            override val menuTooltip = "Table options"
            override val columns = "Columns"
            override val resetColumnWidths = "Reset column widths"
        }

        override val detailsPane = object : BlazeStrings.Downloads.DetailsPane {
            override val tabGeneral = "General"
            override val tabFiles = "Files"
            override val emptyHint = "Select a download to see details"
            override val hide = "Hide details"
            override val show = "Show details"
            override val options = object : BlazeStrings.Downloads.DetailsPane.ToolWindowOptions {
                override val menuTooltip = "Tool Window Options"
                override val groupTabs = "Group Tabs"
                override val viewMode = "View Mode"
                override val moveTo = "Move To"
                override val resize = "Resize"
                override val dockPinned = "Dock Pinned"
                override val dockUnpinned = "Dock Unpinned"
                override val undock = "Undock"
                override val float = "Float"
                override val window = "Window"
                override val leftTop = "Left Top"
                override val leftBottom = "Left Bottom"
                override val rightTop = "Right Top"
                override val rightBottom = "Right Bottom"
                override val bottomLeft = "Bottom Left"
                override val bottomRight = "Bottom Right"
                override val stretchLeft = "Stretch to Left"
                override val stretchRight = "Stretch to Right"
                override val stretchTop = "Stretch to Top"
                override val stretchBottom = "Stretch to Bottom"
                override val maximize = "Maximize Tool Window"
            }
        }

        override val backgroundTasks = object : BlazeStrings.Downloads.BackgroundTasks {
            override val title = "Background Tasks"
            override val empty = "No downloads in progress"
            override fun runningCount(n: Int) = if (n == 1) "1 download in progress" else "$n downloads in progress"
            override fun footer(active: Int, queued: Int): String = buildString {
                if (active > 0) append(if (active == 1) "1 active" else "$active active")
                if (queued > 0) {
                    if (isNotEmpty()) append(", ")
                    append(if (queued == 1) "1 queued" else "$queued queued")
                }
            }
        }

        override val dialogs = object : BlazeStrings.Downloads.Dialogs {
            override val addTitle = "Add download"
            override val addUrlPlaceholder = "https://…  or  magnet:?xt=…"
            override val addFromFile = "Add from File"
            override val selectSourceFile = "Select Torrent or Text File"
            override val addDownload = "Download"
            override val addAction = "Add"
            override val backToEdit = "Back to edit"
            override val downloadSource = "Download source:"
            override val saveTo = "Save to:"
            override val metadataResolved = "Metadata resolved:"
            override val url = "URL:"
            override val size = "Size:"
            override val fetchingMetadata = "Fetching metadata..."
            override val fetchingItems = "Fetching items..."
            override val sourceWarning = "Not an HTTP(S) link, magnet link or .torrent file. It may fail to download."
            override val files = "Files:"
            override val selectAll = "Select All"
            override val deselectAll = "Deselect All"
            override val itemsSelected = "items selected"
            override val batchTitle = "Batch Add Downloads"
            override val scheduleDelayLabel = "Schedule start delay (minutes):"
            override val scheduleDelayPlaceholder = "0 for immediate start"

            override val pasteFromClipboard = "Paste from clipboard"
            override val clearSource = "Clear"
            override val advancedOptions = "Advanced options"
            override val sourceKindMagnet = "Magnet link"
            override val sourceKindTorrent = "Torrent file"
            override val sourceKindHttp = "HTTP link"
            override val emptyLinkFile = "No links found in this file"
            override fun linkCount(count: Int) = "$count links"
            override fun sourceFileReadFailed(reason: String?) =
                "Couldn't read the file${reason?.let { ": $it" } ?: ""}"
            override fun freeSpace(size: String) = "Free space: $size"
            override val destinationWillCreate = "Folder doesn't exist yet and will be created"
            override val destinationNotDirectory = "This path is a file, not a folder"
            override val destinationNotWritableHint = "This folder isn't writable"
            override val destinationInvalidPath = "Invalid path"
            
            override val removeTitle = "Remove download"
            override fun removeMessage(name: String) = "Remove $name?"
            override val removeOnly = "Remove from list only"
            override val removeOnlyHint = "The downloaded file stays on disk."
            override val removeAndDelete = "Remove from list and delete file from disk"
            override val removeAndDeleteHint = "The file is permanently deleted and can't be restored."
            override val dontAskAgain = "Don't ask again"
            override val removeAction = "Remove"
            override val deleteAction = "Delete"

            override val conflictTitle = "File Conflict"
            override fun conflictMessage(name: String) = "The file '$name' already exists at the destination."
            override val overwriteAction = "Overwrite"
            override val renameAction = "Rename"
            override val skipAction = "Skip"

            override val handlerChoiceTitle = "Choose link handler"
            override val handlerChoicePrompt = "More than one handler can process this link. Which one should be used?"
            override val resolveAction = "Resolve"
            override val resolvingLink = "Resolving link..."
            override val noHandlerForLink = "No installed handler can process this link."
            override fun handlerWillBeUsed(name: String) =
                "Will be resolved using the $name extension."
            override fun handlerWillAsk(count: Int) =
                "$count extensions can process this link — you'll be asked which one to use."

            override val plainPage = object : BlazeStrings.Downloads.Dialogs.PlainPage {
                override val title = "No handler for this link"
                override fun message(url: String) =
                    "No installed extension can take this link apart, so Blaze can't find a file inside it: $url"
                override val detail =
                    "If you go ahead, Blaze saves exactly what the site returns for this link — the page " +
                        "itself, usually an HTML file, rather than the file the page points at."
                override val confirm = "Download the page anyway"
            }

            override val details = object : BlazeStrings.Downloads.Dialogs.Details {
                override val title = "Download details"
                override val name = "Name"
                override val status = "Status"
                override val location = "Save to"
                override val source = "Source link"
                override val size = "Size"
                override val speed = "Speed"
                override val eta = "Time remaining"
                override val peers = "Peers"
                override val added = "Added"
                override val scheduled = "Scheduled to start"
                override val files = "Files"
                override val error = "Error"
                override val resumeSupport = "Pause & resume"
            }

            override val pauseRisk = object : BlazeStrings.Downloads.Dialogs.PauseRisk {
                override val title = "Pause this download?"
                override fun message(name: String) = "Pause $name?"
                override fun detail(percent: Int) = if (percent > 0) {
                    "This server can't continue an interrupted download, so pausing discards the " +
                        "$percent% already fetched and starts over from zero next time."
                } else {
                    "This server can't continue an interrupted download, so it will start over from zero."
                }
                override val confirm = "Pause anyway"
            }

            override val clearCompleted = object : BlazeStrings.Downloads.Dialogs.ClearCompleted {
                override val title = "Clear completed downloads"
                override fun message(count: Int) = if (count == 1) {
                    "Remove 1 finished download from the list?"
                } else {
                    "Remove $count finished downloads from the list?"
                }
                override val detail = "The files themselves stay on disk; only their entries leave the list."
                override fun seedingDetail(count: Int) = if (count == 1) {
                    "1 of them is still seeding; clearing it stops sharing that file."
                } else {
                    "$count of them are still seeding; clearing them stops those shares."
                }
                override val confirm = "Clear the list"
            }

            override val destinationNotWritable = "The save folder can't be written to. Go back and choose another one."
            override fun notEnoughSpace(needed: String, free: String) =
                "Not enough space on this drive: $needed needed, $free free."
            override val sourceCannotResume =
                "This server can't continue an interrupted download. If you pause it, it starts over from zero."
            override val metadataUnresolvedTorrent =
                "The torrent's details couldn't be resolved yet — they'll appear in the list once peers answer."
            override fun httpProbeFailed(url: String) =
                "Couldn't reach this link: $url. It may be offline or misspelled."
        }

        override val status = object : BlazeStrings.Downloads.Status {
            override val downloading = "Downloading"
            override val paused = "Paused"
            override val completed = "Completed"
            override val queued = "Queued"
            override val error = "Error"
            override val cancelling = "Cancelling"
            override val seeding = "Seeding"
            override val scheduled = "Scheduled"
        }

        override val priority = object : BlazeStrings.Downloads.Priority {
            override val idle = "Idle"
            override val low = "Low"
            override val normal = "Normal"
            override val high = "High"
            override val label = "Priority"
        }
        
        override fun progress(downloaded: String, total: String) = "$downloaded / $total"
        override fun completedCount(count: Int) = if (count == 1) "1 download completed" else "$count downloads completed"
        override fun peers(count: Int) = if (count == 1) "1 peer" else "$count peers"
        override fun speed(speed: String) = "$speed/s"
        override fun filesCount(count: Int) = if (count == 1) "1 file" else "$count files"

        override val actions = object : BlazeStrings.Downloads.Actions {
            override val pause = "Pause"
            override val resume = "Resume"
            override val retry = "Retry"
            override val cancel = "Cancel"
            override val remove = "Remove"
            override val showFiles = "Show files"
            override val openFile = "Open"
            override val showInFolder = "Show in folder"
            override val openSourceLink = "Open source link in browser"
            override val copyLink = "Copy download link"
            override val copyFilePath = "Copy file location"
            override val showDetails = "Show details"
            override val reDownload = "Re-download"
            override val clear = "Clear from list"
        }

        override val portable = object : BlazeStrings.Downloads.Portable {
            override val indicator = "Portable resume enabled"
            override val dialogTitle = "Resume portable download"
            override fun kindHttp(name: String) = "HTTP download \u2014 $name"
            override fun kindTorrent(name: String) = "Torrent download \u2014 $name"
            override val source = "Source"
            override val expectedSize = "Expected size"
            override val available = "Already downloaded"
            override val saveLocation = "Save to"
            override val resumable = "Resumable"
            override val staleWarning = "This progress may be outdated; existing data will be re-verified before resuming."
            override val credentialsNote = "If the source requires sign-in, provide credentials on this PC to resume."
            override val importAction = "Resume download"
            override fun notPortable(path: String) = "\u201c$path\u201d is not a Blaze portable download."
            override val imported = "Portable download added to the queue."
        }

        override val advisories = object : BlazeStrings.Downloads.Advisories {
            override val cannotResume = "Can't resume"
            override val waitingForPeers = "Waiting for peers"
            override val fileMissing = "File missing"
            override val resumeSupported = "Can resume where it stopped"
            override val titleCompleted = "Download completed"
            override val titleFailed = "Download failed"
            override val titleRestarted = "Download restarted"
            override fun completed(name: String) = "$name finished downloading."
            override fun failed(name: String, reason: String) = "$name failed: $reason"
            override fun restarted(name: String, percent: Int) =
                "$name couldn't continue from $percent%, so it's downloading again from the start."
            override fun resumeStartsOver(name: String) =
                "$name can't be continued by its server, so resuming starts it over from zero."
            override fun pauseAllCosts(count: Int) = if (count == 1) {
                "1 download can't be resumed by its server; pausing it discards its progress."
            } else {
                "$count downloads can't be resumed by their server; pausing them discards their progress."
            }
            override fun duplicateSource(name: String) = "$name is already in the download list."
            override fun removeInProgress(percent: Int) =
                "This download is only $percent% done; removing it throws that data away."
            override fun alertCannotResume(count: Int) = if (count == 1) {
                "1 download can't be resumed"
            } else {
                "$count downloads can't be resumed"
            }
            override fun alertWaitingPeers(count: Int) = if (count == 1) {
                "1 download is waiting for peers"
            } else {
                "$count downloads are waiting for peers"
            }
        }
    }

    override val filePicker = object : BlazeStrings.FilePicker {
        override val titleFolder = "Select folder"
        override val titleFile = "Select file"
        override val home = "Home"
        override val newFolder = "New folder"
        override val refresh = "Refresh"
        override val showHidden = "Show hidden files"
        override val hideHidden = "Hide hidden files"
        override val pathPlaceholder = "Path"
        override val hintFolder = "Select a folder in the tree, or type a path above."
        override val hintFile = "Select a file in the tree, or type a path above."
        override fun supportedTypes(extensions: String) = "Supported types: $extensions"

        override val newFolderTitle = "New folder"
        override val newFolderName = "Name:"
        override fun newFolderCreatedIn(parent: String) = "Will be created in $parent"
        override val newFolderError = "Couldn't create the folder"

        override val errorInvalidPath = "Invalid path"
        override val errorPathNotExists = "Path doesn't exist"
        override val errorNotAFolder = "Not a folder"
        override val errorUnsupportedFileType = "This file type isn't supported"
        override val errorInvalidName = "Invalid name"
        override val errorIllegalCharacters = "Name contains illegal characters"
        override val errorAlreadyExists = "A file or folder with this name already exists"

        override val collapse = "Collapse"
        override val expand = "Expand"
    }

    override val statusSummary = object : BlazeStrings.StatusSummary {
        override val ready = "Ready"
        override fun downloading(count: Int) = "$count downloading"
        override fun queued(count: Int) = "$count queued"
        override fun paused(count: Int) = "$count paused"
        override fun failed(count: Int) = "$count failed"
        override fun snailMode(rate: String) = "Snail Mode · $rate"
    }

    override val tray = object : BlazeStrings.Tray {
        override val show = "Show Window"
        override val hide = "Hide Window"
        override val pauseAll = "Pause All Downloads"
        override val resumeAll = "Resume All Downloads"
        override val quit = "Quit Blaze"
        override val enableSnailMode = "Enable Snail Mode"
        override val disableSnailMode = "Disable Snail Mode"
    }

    override val menu = object : BlazeStrings.Menu {
        override val mainMenu = "Main Menu"
        override val search = "Search"
        override val searchEverywhere = "Search Everywhere…"
        override val newDownload = "New Download…"
        override val settings = "Settings…"
        override val pauseAll = "Pause All"
        override val resumeAll = "Resume All"
        override val toggleDetails = "Toggle Details"
        override val about = "About Blaze"
        override val exit = "Exit"
        override val speedLimit = "Speed limit"
        override val fullSpeed = "Full speed"
        override val snailMode = "Snail mode"
        override val aboutTitle = "About Blaze"
        override val aboutBody = "A fast, modern download manager."
    }
    
    override val search = object : BlazeStrings.Search {
        override val placeholder = "Search downloads, actions, settings…"
        override val tabAll = "All"
        override val tabDownloads = "Downloads"
        override val tabActions = "Actions"
        override val tabSettings = "Settings"
        override val typeToSearch = "Type to search"
        override val nothingFound = "Nothing found"
        override val sectionDownloads = "Downloads"
        override val sectionActions = "Actions"
        override val sectionSettings = "Settings"
    }

    override val settings = object : BlazeStrings.Settings {
        override val title = "Settings"
        override val placeholder = "App configuration will appear here."
        override val searchPlaceholder = "Search settings…"
        override val clearSearch = "Clear search"
        override val searchNoMatches = "No matching settings"
        override val generalCategory = "General"
        override val downloadsCategory = "Downloads"
        override val filesCategory = "Files"
        override val logsCategory = "Logs"
        override val concurrencyHeader = "Concurrency & Queuing"
        override val maxConcurrentDownloadsLabel = "Maximum concurrent downloads"
        override val maxConcurrentDownloadsDesc = "Controls how many downloads can actively transfer at the same time. Downloads exceeding this limit remain queued until a slot becomes available."
        override val maxConnectionsLabel = "Maximum connections per download"
        override val maxConnectionsDesc = "The number of concurrent HTTP connections/segments or torrent streams established per individual download task."
        override val defaultPriorityLabel = "Default priority for new downloads"
        override val defaultPriorityDesc = "How urgent a freshly added download is relative to the others. Higher priorities start first when a slot frees and get a larger share of the connections; lower ones yield."
        override val bandwidthHeader = "Bandwidth Limit"
        override val speedLimitEnabledLabel = "Limit download speed globally"
        override val speedLimitLabel = "Download speed limit (KB/s)"
        override val retryHeader = "Automatic Retries"
        override val autoRetryLabel = "Automatically retry failed downloads"
        override val maxRetriesLabel = "Maximum retries"
        override val retryDelayLabel = "Retry delay (seconds)"
        override val exponentialBackoffLabel = "Increase the delay exponentially between retries"
        override val networkHeader = "Network & Protocol"
        override val userAgentLabel = "HTTP User-Agent"
        override val userAgentDesc = "Sent with every HTTP request. Leave empty to use the built-in default."
        override val maxRedirectsLabel = "Maximum redirects"
        override val maxRedirectsDesc = "How many HTTP redirects to follow before giving up. Set 0 to disable redirects."
        override val torrentHeader = "Torrent"
        override val maxPeerConnectionsLabel = "Maximum peer connections"
        override val maxPeerConnectionsDesc = "Upper bound on simultaneous peer connections per torrent client."
        override val seedingEnabledLabel = "Keep seeding after a download completes"
        override val seedTimeLimitLabel = "Seed for (minutes)"
        override val unlimitedSeedingLabel = "Seed indefinitely (until stopped manually)"
        override val seedRatioLimitLabel = "Stop seeding at share ratio"
        override val seedRatioLimitDesc = "Stop seeding once the uploaded/downloaded ratio reaches this value. Set 0 for no ratio limit."
        override val snailModeEnabledLabel = "Snail Mode — crawl all downloads"
        override val snailSpeedLabel = "Snail speed (KB/s)"
        override val snailSpeedDesc = "Engine-wide ceiling applied while Snail Mode is on. It overrides the normal speed limit so your connection stays free for other work."
        override val appearanceHeader = "Appearance"
        override val themeLabel = "Theme"
        override val languageLabel = "Language"
        override val themeSystemOption = "Follow system"
        override val themeLightOption = "Light"
        override val themeDarkOption = "Dark"
        override val startupHeader = "Startup & Queue Behavior"
        override val resumeOnStartupLabel = "Resume downloads automatically on application startup"
        override val startQueuedOnStartupLabel = "Start queued downloads automatically on application startup"
        override val systemIntegrationHeader = "System Integration"
        override val trayEnabledLabel = "Show an icon in the system tray"
        override val trayEnabledDesc = "Keeps Blaze accessible in the background. Unavailable on desktops without a tray."
        override val minimizeToTrayLabel = "Hide the window to the tray when closed"
        override val runAtStartupLabel = "Run Blaze at system startup"
        override val runAtStartupDesc = "Registers Blaze to start automatically when you log in."
        override val clipboardMonitoringLabel = "Monitor the clipboard for downloaded links"
        override val clipboardMonitoringDesc = "When you copy a web link, magnet link or .torrent path, Blaze offers it as a new download. Nothing is added until you confirm."
        override val destinationHeader = "Download Destinations"
        override val defaultDownloadDirLabel = "Default download directory"
        override val askWhereToSaveLabel = "Ask where to save each download before starting"
        override val fileConflictsHeader = "File Conflicts"
        override val fileConflictBehaviorLabel = "When a file already exists at the destination"
        override val askOption = "Ask what to do"
        override val overwriteOption = "Overwrite the existing file"
        override val skipOption = "Skip the download"
        override val renameOption = "Rename the file automatically"
        override val validationPositiveNumber = "Must be a positive number greater than 0"
        override val validationNonNegativeNumber = "Must be a non-negative number (0 or greater)"

        override val logging = object : BlazeStrings.Settings.Logging {
            override val header = "Logging"
            override val levelLabel = "Log level"
            override val levelDesc = "Controls how much detail Blaze writes to its logs. Debug and Trace are mostly useful when diagnosing problems."
            override val offOption = "Off"
            override val errorOption = "Errors"
            override val warnOption = "Warnings"
            override val infoOption = "Info"
            override val debugOption = "Debug"
            override val traceOption = "Trace"
            override val fileLoggingLabel = "Write logs to files"
            override val fileLoggingDesc = "When off, logs are only printed to the console (visible when Blaze is started from a terminal)."
            override val filesHeader = "Log Files"
            override val logDirLabel = "Log folder"
            override val logDirDesc = "Current and rotated (compressed) logs live here. Rotated files are kept for 7 days and capped at 100 MB in total."
            override val openFolderAction = "Open folder…"
        }

        override val handlers = object : BlazeStrings.Settings.Handlers {
            override val category = "Link Handlers"
            override val supportedSitesHeader = "Supported sites"
            override val supportedSitesDesc = "Handlers turn page links into direct download links."
            override val alwaysAskLabel = "Always ask which handler to use when several match"
            override val alwaysAskDesc = "When off, the first matching handler is used automatically. A single matching handler is always used without asking."
            override val extensionsDirHeader = "Extensions"
            override val extensionsDirLabel = "Extensions folder"
            override val extensionsDirDesc = "Handler jars dropped into this folder are loaded at startup."
            override val installAction = "Install extension..."
            override val reloadAction = "Reload"
            override val removeAction = "Remove"
            override val emptyHandlers = "No handlers are installed."
            override val builtinTag = "Built-in"
            override val pluginTag = "Extension"
        }

        override val themes = object : BlazeStrings.Settings.Themes {
            override val category = "Themes"
            override val colorThemeHeader = "Color theme"
            override val colorThemeDesc = "Pick a theme to restyle Blaze's accent and surfaces. Add more by installing theme extensions."
            override val activeRadio = "Active"
            override val themesDirHeader = "Theme extensions"
            override val themesDirLabel = "Themes folder"
            override val themesDirDesc = "Theme jars dropped into this folder are loaded at startup."
            override val installAction = "Install theme..."
            override val reloadAction = "Reload"
            override val removeAction = "Remove"
            override val builtinTag = "Built-in"
            override val pluginTag = "Extension"
            override val chromeHeader = "Window chrome"
            override val ambientGlowLabel = "Ambient title-bar glow"
            override val ambientGlowDesc = "A soft accent-colored glow at the top-left of the window frame, behind the title bar and the side stripe."
        }

        override val notifications = object : BlazeStrings.Settings.Notifications {
            override val category = "Notifications"
            override val header = "Desktop Notifications"
            override val enabledLabel = "Post download events to the desktop"
            override val enabledDesc = "System toasts and notification-center entries, on top of the notices shown inside the window."
            override val completedLabel = "A download finished"
            override val failedLabel = "A download failed"
            override val restartedLabel = "An unfinished download had to start over"
            override val attentionHeader = "When Blaze Should Speak"
            override val whileInSightLabel = "Also notify while the Blaze window is visible and focused"
            override val whileInSightDesc = "Left off, notices only go out when Blaze is hidden to the tray or in the background - the list already shows what happened while you are looking at it."
            override val unsupportedNote = "This desktop offers no notification surface, so Blaze cannot post system notices."
        }
    }
    
    override val errors = object : BlazeStrings.Errors {
        override val networkUnavailable = "Network unavailable"
        override val timeout = "Connection timed out"
        override val unauthorized = "Unauthorized access"
        override val notFound = "File not found"
        override val diskFull = "Disk full"
        override val rangeUnsupported = "Resuming not supported"
        override val invalidTorrent = "Invalid torrent file"
        override val metadataTimeout = "Failed to fetch metadata"
        override val cancelled = "Download cancelled"
        override fun networkFailure(message: String) = "Network failure: $message"
        override fun diskError(message: String) = "Disk error: $message"
        override fun unknown(message: String) = "Unknown error: $message"
    }
}
