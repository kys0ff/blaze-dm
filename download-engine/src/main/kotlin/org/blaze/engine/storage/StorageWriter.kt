package org.blaze.engine.storage

import org.blaze.engine.settings.DownloadSettings
import org.blaze.engine.settings.FileConflictBehavior
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class StorageWriter(
    private val destination: Path,
    private val settings: DownloadSettings
) {
    val destinationFile: File
    val partialFile: File

    init {
        var dest = destination.toFile()
        var part = destination.resolveSibling("${destination.fileName}.part").toFile()

        if (dest.exists()) {
            when (settings.fileConflictBehavior) {
                FileConflictBehavior.SKIP -> {
                    // Handled at a higher layer or before starting execution
                }
                FileConflictBehavior.OVERWRITE -> {
                    dest.delete()
                    part.delete()
                }
                FileConflictBehavior.RENAME, FileConflictBehavior.ASK -> {
                    val fullStr = destination.fileName.toString()
                    val baseName = fullStr.substringBeforeLast(".")
                    val extension = if (fullStr.contains(".")) fullStr.substringAfterLast(".") else ""
                    val extStr = if (extension.isNotEmpty()) ".$extension" else ""
                    var count = 1
                    var newFile = destination.resolveSibling("$baseName ($count)$extStr").toFile()
                    while (newFile.exists()) {
                        count++
                        newFile = destination.resolveSibling("$baseName ($count)$extStr").toFile()
                    }
                    dest = newFile
                    part = newFile.resolveSibling("${newFile.name}.part")
                }
            }
        }
        destinationFile = dest
        partialFile = part
    }

    fun preparePartialFile(isResumed: Boolean) {
        RandomAccessFile(partialFile, "rw").use { raf ->
            if (!isResumed) {
                raf.setLength(0)
            }
        }
    }

    fun finalizeFile() {
        Files.move(partialFile.toPath(), destinationFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun deletePartialFile() {
        partialFile.delete()
    }
}
