package org.blaze.presentation.screens.filepicker.model

/**
 * Which place in the app opened the picker, and therefore which remembered directory it owns.
 *
 * Every call site declares its own context, so the folders a user browses to in one dialog never
 * become the starting point of another: picking a jar in an extensions folder should not move the
 * "save download here" picker off the downloads drive. An entry is a stable memory slot - its
 * [name] is the persistence key, so renaming one only costs the remembered folder (the picker then
 * opens at home), never a crash or a wrong directory.
 *
 * Two dialogs that ask for the same thing share one context; they should reopen in the same place.
 */
enum class FilePickerContext {
    /** The folder a download (or a re-linked portable artifact) is written to. */
    DownloadDestination,

    /** The `.torrent` / link-list file fed into the New Download dialog. */
    DownloadSourceFile,

    /** The moved artifact a portable import has to be located on disk. */
    PortableArtifact,

    /** The install-a-link-handler jar dialog (Extensions panel and Settings' handlers section). */
    ExtensionJar,

    /** The install-a-theme jar dialog (Settings' themes section). */
    ThemeJar,

    /** Settings' default download directory - the app-wide fallback, distinct from one download's folder. */
    DefaultDownloadDir,
}
