package org.blaze.presentation.screens.filepicker.filesystem

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blaze.presentation.screens.filepicker.model.FileSystemNode
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

class FileSystemRepository(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    fun getRoots(): List<FileSystemNode> {
        return FileSystems.getDefault().rootDirectories.map {
            FileSystemNode(path = it, isDirectory = true, isHidden = false)
        }
    }

    suspend fun listChildren(
        dir: Path,
        directoriesOnly: Boolean,
        fileFilter: (Path) -> Boolean
    ): List<FileSystemNode> = withContext(ioDispatcher) {
        try {
            Files.newDirectoryStream(dir).use { stream ->
                stream.mapNotNull { p ->
                    val isDir = Files.isDirectory(p)
                    if (!isDir && (directoriesOnly || !fileFilter(p))) {
                        null
                    } else {
                        FileSystemNode(p, isDir, isHiddenPath(p))
                    }
                }
            }.sortedWith(compareBy({ !it.isDirectory }, { it.displayName.lowercase() }))
        } catch (_: IOException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun isHiddenPath(path: Path): Boolean {
        return path.fileName?.toString()?.startsWith(".") == true ||
                runCatching { Files.isHidden(path) }.getOrDefault(false)
    }

    suspend fun createDirectory(parent: Path, name: String): Result<Path> = withContext(ioDispatcher) {
        runCatching { Files.createDirectory(parent.resolve(name)) }
    }

    fun exists(path: Path): Boolean {
        return Files.exists(path)
    }

    fun isDirectory(path: Path): Boolean {
        return Files.isDirectory(path)
    }

    fun nearestExisting(path: Path): Path {
        return generateSequence(path.toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.exists(it) }
            ?: Path.of(System.getProperty("user.home"))
    }
}
