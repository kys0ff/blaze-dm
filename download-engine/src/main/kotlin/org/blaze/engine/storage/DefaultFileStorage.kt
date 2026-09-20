package org.blaze.engine.storage

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DefaultFileStorage : FileStorage {
    override fun getPartialFile(destination: Path): File =
        destination.resolveSibling("${destination.fileName}.part").toFile()

    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun size(path: Path): Long = Files.size(path)

    override fun delete(path: Path) {
        Files.deleteIfExists(path)
    }

    override fun move(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun openForWrite(path: Path): FileWriteSession {
        val raf = RandomAccessFile(path.toFile(), "rw")
        return RafFileWriteSession(raf)
    }

    override fun ensureDirectory(path: Path) {
        Files.createDirectories(path)
    }

    override fun deleteRecursively(path: Path) {
        path.toFile().deleteRecursively()
    }

    private class RafFileWriteSession(private val raf: RandomAccessFile) : FileWriteSession {
        override fun seek(offset: Long) = raf.seek(offset)

        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            raf.write(buffer, offset, length)

        override fun setLength(length: Long) = raf.setLength(length)

        override fun close() = raf.close()
    }
}
