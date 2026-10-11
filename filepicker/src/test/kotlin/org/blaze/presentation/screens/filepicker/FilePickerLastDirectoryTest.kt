package org.blaze.presentation.screens.filepicker

import org.blaze.i18n.EnStrings
import org.blaze.presentation.screens.filepicker.filesystem.FileSystemRepository
import org.blaze.presentation.screens.filepicker.memory.FilePickerLastDirectoryStore
import org.blaze.presentation.screens.filepicker.memory.InMemoryFilePickerLastDirectoryStore
import org.blaze.presentation.screens.filepicker.memory.directoryForPick
import org.blaze.presentation.screens.filepicker.model.FilePickerContext
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The picker's per-context directory memory: what a dialog opens at, and what an accepted pick writes
 * back. The whole point of keying the memory by [FilePickerContext] is that browsing for an extension
 * jar and choosing a download folder are different errands, so neither may move the other - which is
 * exactly what these tests pin, alongside the fallbacks a stale or missing memory must degrade to.
 */
class FilePickerLastDirectoryTest {

    private val tempDir = Files.createTempDirectory("blaze-picker-memory")

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun model(store: FilePickerLastDirectoryStore) =
        FilePickerScreenModel(FileSystemRepository(), store)

    /** Opens a picker the way the dialog does: the caller's path is optional, the context never is. */
    private fun FilePickerScreenModel.open(
        mode: FilePickerMode,
        context: FilePickerContext,
        initialPath: Path? = null,
    ) {
        onEvent(
            FilePickerEvent.Init(
                mode = mode,
                context = context,
                initialPath = initialPath,
                title = null,
                description = null,
                confirmText = null,
                fileFilter = { true },
                strings = EnStrings,
            )
        )
    }

    private val FilePickerScreenModel.openedAt: String get() = state.value.pathText.text

    @Test
    fun `a picker with no path of its own reopens at the directory its context used last`() {
        val jars = Files.createDirectories(tempDir.resolve("jars"))
        val model = model(InMemoryFilePickerLastDirectoryStore(mapOf(FilePickerContext.ExtensionJar to jars)))

        model.open(FilePickerMode.File, FilePickerContext.ExtensionJar)

        assertEquals(jars.toString(), model.openedAt)
    }

    @Test
    fun `one context never moves another context's picker`() {
        val jars = Files.createDirectories(tempDir.resolve("jars"))
        val model = model(InMemoryFilePickerLastDirectoryStore(mapOf(FilePickerContext.ExtensionJar to jars)))

        // The download folder picker has no history, so it starts at home - not in the jar folder the
        // extension dialog left behind.
        model.open(FilePickerMode.Directory, FilePickerContext.DownloadDestination)

        assertEquals(Path.of(System.getProperty("user.home")).toString(), model.openedAt)
    }

    @Test
    fun `a path the caller supplies outranks the remembered directory`() {
        val remembered = Files.createDirectories(tempDir.resolve("remembered"))
        val supplied = Files.createDirectories(tempDir.resolve("supplied"))
        val model = model(InMemoryFilePickerLastDirectoryStore(mapOf(FilePickerContext.ExtensionJar to remembered)))

        model.open(FilePickerMode.Directory, FilePickerContext.ExtensionJar, initialPath = supplied)

        assertEquals(supplied.toString(), model.openedAt, "what the screen is showing beats the memory")
    }

    @Test
    fun `accepting a file records the folder holding it, for that context alone`() {
        val store = InMemoryFilePickerLastDirectoryStore()
        val model = model(store)
        model.open(FilePickerMode.File, FilePickerContext.ExtensionJar, initialPath = tempDir)
        val jar = Files.writeString(tempDir.resolve("handler.jar"), "not really a jar")

        model.onEvent(FilePickerEvent.Confirm(jar))

        assertEquals(tempDir, store.lastDirectory(FilePickerContext.ExtensionJar))
        assertNull(store.lastDirectory(FilePickerContext.DownloadDestination))
    }

    @Test
    fun `accepting a folder records the folder itself`() {
        val store = InMemoryFilePickerLastDirectoryStore()
        val model = model(store)
        model.open(FilePickerMode.Directory, FilePickerContext.DownloadDestination, initialPath = tempDir)
        val destination = Files.createDirectories(tempDir.resolve("movies"))

        model.onEvent(FilePickerEvent.Confirm(destination))

        assertEquals(destination, store.lastDirectory(FilePickerContext.DownloadDestination))
    }

    @Test
    fun `cancelling a picker leaves the remembered directory alone`() {
        val remembered = Files.createDirectories(tempDir.resolve("keep"))
        val store = InMemoryFilePickerLastDirectoryStore(mapOf(FilePickerContext.ThemeJar to remembered))
        val model = model(store)

        model.open(FilePickerMode.File, FilePickerContext.ThemeJar)
        model.onEvent(FilePickerEvent.Dismiss)

        assertEquals(remembered, store.lastDirectory(FilePickerContext.ThemeJar))
    }

    @Test
    fun `a remembered directory that is gone opens at its nearest surviving ancestor`() {
        // An unmounted drive or a deleted folder must not open the picker on a path that shows nothing.
        val vanished = tempDir.resolve("unmounted/share")
        val model = model(InMemoryFilePickerLastDirectoryStore(mapOf(FilePickerContext.PortableArtifact to vanished)))

        model.open(FilePickerMode.File, FilePickerContext.PortableArtifact)

        assertEquals(tempDir.toString(), model.openedAt)
    }

    @Test
    fun `directoryForPick maps a file onto its folder and a folder onto itself`() {
        val folder = tempDir.resolve("folder")
        assertEquals(folder, directoryForPick(FilePickerMode.Directory, folder))
        assertEquals(tempDir, directoryForPick(FilePickerMode.File, tempDir.resolve("plugin.jar")))
        // A bare relative name has no parent to fall back on: it stays itself instead of crashing.
        assertEquals(Path.of("plugin.jar"), directoryForPick(FilePickerMode.File, Path.of("plugin.jar")))
    }
}
