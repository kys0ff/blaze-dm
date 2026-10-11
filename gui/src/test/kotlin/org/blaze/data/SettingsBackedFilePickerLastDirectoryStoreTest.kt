package org.blaze.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.blaze.presentation.screens.filepicker.model.FilePickerContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The durable half of the picker memory: one remembered directory per context, in `app.json`.
 *
 * Two properties are worth more than the happy path. The first is that the memory is per context, so
 * installing a jar never relocates the download-folder picker. The second is that saving it must not
 * sweep up a half-finished edit the settings screen is still holding - the picker writes from outside
 * that draft-then-apply flow, and its save has to touch only its own field.
 */
class SettingsBackedFilePickerLastDirectoryStoreTest {

    private val tempDir = Files.createTempDirectory("blaze-picker-store-test")

    // The store persists on an application-lifetime scope, exactly as Blaze wires it: the dialog that
    // picked the folder is gone before the write reaches the file.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempDir.toFile().deleteRecursively()
    }

    private fun store(repository: AppSettingsRepository) =
        SettingsBackedFilePickerLastDirectoryStore(repository, scope)

    /** Waits for the launched write to land instead of guessing at a duration. */
    private fun awaitOnDisk(repository: AppSettingsRepository, context: FilePickerContext, path: Path) {
        repeat(200) {
            if (repository.persisted.filePickerLastDirectories[context.name] == path.toString()) return
            Thread.sleep(10)
        }
        fail("The remembered directory never reached the persisted snapshot")
    }

    @Test
    fun `each context keeps its own remembered directory`() {
        val repository = AppSettingsRepository(tempDir)
        val pickerStore = store(repository)
        val jars = Files.createDirectories(tempDir.resolve("jars"))
        val downloads = Files.createDirectories(tempDir.resolve("downloads"))

        pickerStore.recordDirectory(FilePickerContext.ExtensionJar, jars)
        assertEquals(jars, pickerStore.lastDirectory(FilePickerContext.ExtensionJar))

        pickerStore.recordDirectory(FilePickerContext.DownloadDestination, downloads)
        assertEquals(downloads, pickerStore.lastDirectory(FilePickerContext.DownloadDestination))

        // A context nobody has picked in yet has no memory, so its picker opens at home instead.
        assertNull(pickerStore.lastDirectory(FilePickerContext.ThemeJar))
        assertEquals(jars, pickerStore.lastDirectory(FilePickerContext.ExtensionJar))
    }

    @Test
    fun `the memory outlives the process that made it`() {
        val repository = AppSettingsRepository(tempDir)
        val jars = Files.createDirectories(tempDir.resolve("jars"))

        store(repository).recordDirectory(FilePickerContext.ExtensionJar, jars)
        awaitOnDisk(repository, FilePickerContext.ExtensionJar, jars)

        // A fresh repository over the same directory is what a restart is.
        val afterRestart = store(AppSettingsRepository(tempDir))
        assertEquals(jars, afterRestart.lastDirectory(FilePickerContext.ExtensionJar))
        assertTrue(Files.exists(tempDir.resolve("app.json")))
    }

    @Test
    fun `a remembered directory that is no longer a folder is not offered`() {
        val repository = AppSettingsRepository(tempDir)
        val jars = Files.createDirectories(tempDir.resolve("jars"))
        val pickerStore = store(repository)
        pickerStore.recordDirectory(FilePickerContext.ExtensionJar, jars)
        awaitOnDisk(repository, FilePickerContext.ExtensionJar, jars)

        Files.delete(jars)

        assertNull(store(AppSettingsRepository(tempDir)).lastDirectory(FilePickerContext.ExtensionJar))
    }

    @Test
    fun `a remembered value that is not a path or not a directory is ignored`() = runBlocking {
        val aFile = Files.createFile(tempDir.resolve("plain.txt"))
        val repository = AppSettingsRepository(tempDir)
        repository.updateSettings {
            it.copy(
                filePickerLastDirectories = mapOf(
                    // Hand-edited or stale: a file, and something Path.of() refuses to parse.
                    FilePickerContext.ThemeJar.name to aFile.toString(),
                    FilePickerContext.ExtensionJar.name to "not\u0000parseable",
                )
            )
        }

        val pickerStore = store(repository)
        assertNull(pickerStore.lastDirectory(FilePickerContext.ThemeJar))
        assertNull(pickerStore.lastDirectory(FilePickerContext.ExtensionJar))
    }

    @Test
    fun `saving the memory does not commit a draft edit the settings screen is holding`() {
        val repository = AppSettingsRepository(tempDir)
        // What "Apply" does: live in memory, deliberately not on disk until OK.
        repository.updateSettingsInMemory { it.copy(runAtStartup = true) }

        val jars = Files.createDirectories(tempDir.resolve("jars"))
        store(repository).recordDirectory(FilePickerContext.ExtensionJar, jars)
        awaitOnDisk(repository, FilePickerContext.ExtensionJar, jars)

        assertFalse(repository.persisted.runAtStartup, "an unrelated save must not accept somebody's draft")
        assertTrue(repository.settings.value.runAtStartup, "the draft stays live until the screen applies it")

        val afterRestart = AppSettingsRepository(tempDir).settings.value
        assertFalse(afterRestart.runAtStartup)
        assertEquals(jars.toString(), afterRestart.filePickerLastDirectories[FilePickerContext.ExtensionJar.name])
    }

    @Test
    fun `a recorded path is stored normalized`() {
        val repository = AppSettingsRepository(tempDir)
        val nested = Files.createDirectories(tempDir.resolve("jars"))
        val messy = Path.of(nested.toString(), "..", "jars")

        store(repository).recordDirectory(FilePickerContext.ExtensionJar, messy)
        awaitOnDisk(repository, FilePickerContext.ExtensionJar, nested)

        val stored = repository.settings.value.filePickerLastDirectories[FilePickerContext.ExtensionJar.name]
        assertEquals(nested, stored?.let { Path.of(it) })
    }
}
