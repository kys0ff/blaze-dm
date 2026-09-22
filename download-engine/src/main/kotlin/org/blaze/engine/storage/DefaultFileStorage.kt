package org.blaze.engine.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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

    override fun openPositionedWriter(path: Path): PositionedWriter =
        ChannelPositionedWriter(
            FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE
            )
        )

    /**
     * `RandomAccessFile.setLength` to a larger size extends the file sparsely on every
     * filesystem we target (ext4/btrfs/APFS/NTFS), so it costs metadata, not data writes.
     */
    override fun preallocate(path: Path, size: Long) {
        if (size <= 0) return
        RandomAccessFile(path.toFile(), "rw").use { raf ->
            if (raf.length() < size) raf.setLength(size)
        }
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

    private class ChannelPositionedWriter(private val channel: FileChannel) : PositionedWriter {
        override fun writeAt(position: Long, buffer: ByteArray, offset: Int, length: Int) {
            var written = 0
            while (written < length) {
                val slice = ByteBuffer.wrap(buffer, offset + written, length - written)
                val n = channel.write(slice, position + written)
                if (n <= 0) throw IOException("File channel made no progress writing at $position")
                written += n
            }
        }

        override fun force(meta: Boolean) = channel.force(meta)

        override fun size(): Long = channel.size()

        override fun close() = channel.close()
    }
}
