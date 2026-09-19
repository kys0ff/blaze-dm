package org.blaze.i18n

interface BlazeStrings {
    val common: Common
    val downloads: Downloads
    val settings: Settings
    val errors: Errors
    val filePicker: FilePicker
    val statusSummary: StatusSummary

    interface Common {
        val ok: String
        val cancel: String
        val browse: String
        val path: String
        val name: String
        val home: String
        val newFolder: String
        val refresh: String
        val unknown: String
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
        }

        val dialogs: Dialogs
        interface Dialogs {
            val addTitle: String
            val addUrlPlaceholder: String
            val addDownload: String
            val addAction: String
            val backToEdit: String
            val downloadSource: String
            val saveTo: String
            val metadataResolved: String
            val url: String
            val size: String
            val fetchingMetadata: String
            val sourceWarning: String
            
            val removeTitle: String
            fun removeMessage(name: String): String
            val removeOnly: String
            val removeOnlyHint: String
            val removeAndDelete: String
            val removeAndDeleteHint: String
            val dontAskAgain: String
            val removeAction: String
            val deleteAction: String
        }

        val status: Status
        interface Status {
            val downloading: String
            val paused: String
            val completed: String
            val queued: String
            val error: String
            val cancelling: String
        }
        
        fun progress(downloaded: String, total: String): String
        fun completedCount(count: Int): String
        fun peers(count: Int): String
        fun speed(speed: String): String

        val actions: Actions
        interface Actions {
            val pause: String
            val resume: String
            val retry: String
            val cancel: String
            val remove: String
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
    
    interface Settings {
        val title: String
        val placeholder: String
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
