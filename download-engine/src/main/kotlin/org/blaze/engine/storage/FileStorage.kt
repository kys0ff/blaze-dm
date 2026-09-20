package org.blaze.engine.storage

import java.io.File
import java.nio.file.Path

interface FileStorage {
    fun getPartialFile(destination: Path): File
    fun exists(path: Path): Boolean
    fun size(path: Path): Long
    fun delete(path: Path)
    fun move(source: Path, target: Path)
    fun openForWrite(path: Path): FileWriteSession
    fun ensureDirectory(path: Path)
    fun deleteRecursively(path: Path)
}

interface FileWriteSession : AutoCloseable {
    fun seek(offset: Long)
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun setLength(length: Long)
}
