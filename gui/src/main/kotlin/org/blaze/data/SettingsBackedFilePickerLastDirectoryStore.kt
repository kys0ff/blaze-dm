package org.blaze.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.blaze.presentation.screens.filepicker.memory.FilePickerLastDirectoryStore
import org.blaze.presentation.screens.filepicker.model.FilePickerContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The picker memory Blaze actually has: one remembered directory per [FilePickerContext], written into
 * [AppSettings.filePickerLastDirectories] and therefore kept across launches.
 *
 * A write is folded into the live settings synchronously and only then persisted on [scope]. Both halves
 * matter: the dialog that opens a moment later must already see the new directory (its own screen model
 * dies with it, so it cannot wait for a disk round-trip), while the write to disk belongs to the
 * application scope, because a coroutine started in the closing dialog's scope would be cancelled
 * before it ever reached the file.
 */
class SettingsBackedFilePickerLastDirectoryStore(
    private val appSettingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope,
) : FilePickerLastDirectoryStore {

    private val logger = LoggerFactory.getLogger(SettingsBackedFilePickerLastDirectoryStore::class.java)

    override fun lastDirectory(context: FilePickerContext): Path? =
        appSettingsRepository.settings.value.filePickerLastDirectories[context.name]
            ?.let { stored -> runCatching { Path.of(stored) }.getOrNull() }
            // A folder that is gone (unmounted drive, deleted directory) or turned into a file must not
            // be offered as a starting point; the picker then falls back to home as if it had no memory.
            ?.takeIf { Files.isDirectory(it) }

    override fun recordDirectory(context: FilePickerContext, directory: Path) {
        val normalized = directory.toAbsolutePath().normalize().toString()
        appSettingsRepository.updateSettingsInMemory { it.withLastDirectory(context, normalized) }
        scope.launch {
            // updatePersisted, not updateSettings: the settings screen can be holding an un-applied edit
            // while a picker inside it saves, and this write must not commit that edit on its way past.
            runCatching { appSettingsRepository.updatePersisted { it.withLastDirectory(context, normalized) } }
                .onFailure { logger.warn("Could not persist the last directory of picker {}", context.name, it) }
        }
    }

    private fun AppSettings.withLastDirectory(context: FilePickerContext, path: String): AppSettings =
        copy(filePickerLastDirectories = filePickerLastDirectories + (context.name to path))
}
