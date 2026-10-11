package org.blaze.presentation.screens.filepicker.memory

import org.blaze.presentation.screens.filepicker.model.FilePickerContext
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import java.nio.file.Path

/**
 * Remembers the directory each [FilePickerContext] last worked in, so a picker reopens where the user
 * was instead of at the home folder.
 *
 * The memory is keyed by context on purpose: the folder chosen while installing an extension jar says
 * nothing about where downloads should land, and letting one dialog overwrite another's start point is
 * what turns "remember my location" into noise the user has to fight every time.
 *
 * Implementations answer [lastDirectory] synchronously and from live state, because the next dialog
 * opens straight after the previous one is dismissed and must already see the new value; durable
 * storage may catch up later.
 */
interface FilePickerLastDirectoryStore {

    /** The directory [context] last accepted a pick in, or null when it has no memory yet. */
    fun lastDirectory(context: FilePickerContext): Path?

    /** Makes [directory] the directory [context] opens at next time. Never throws. */
    fun recordDirectory(context: FilePickerContext, directory: Path)
}

/**
 * The directory an accepted pick implies for the memory: a folder is itself, a file is the folder
 * holding it (so a jar or torrent picked out of `~/Downloads/jars` reopens in `~/Downloads`).
 */
fun directoryForPick(mode: FilePickerMode, picked: Path): Path =
    if (mode == FilePickerMode.Directory) picked else picked.parent ?: picked

/**
 * Process-lifetime memory with no disk backing: the double that lets the picker's own tests prove the
 * wiring, and the fallback for a host that has nowhere to persist to (an app that wants the memory to
 * survive a launch binds its own implementation instead).
 */
class InMemoryFilePickerLastDirectoryStore(
    initial: Map<FilePickerContext, Path> = emptyMap(),
) : FilePickerLastDirectoryStore {

    private val directories = initial.toMutableMap()

    override fun lastDirectory(context: FilePickerContext): Path? = directories[context]

    override fun recordDirectory(context: FilePickerContext, directory: Path) {
        directories[context] = directory
    }
}
