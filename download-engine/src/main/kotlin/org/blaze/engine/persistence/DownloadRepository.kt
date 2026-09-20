package org.blaze.engine.persistence

import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DownloadRepository(val storageDir: Path) {
    private val dbFile = storageDir.resolve("downloads.json").toFile()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun loadAll(): List<DownloadRecord> {
        if (!dbFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<DownloadRecord>>(dbFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(records: List<DownloadRecord>) {
        val targetPath = dbFile.toPath()
        val parentDir = targetPath.parent ?: return
        Files.createDirectories(parentDir)
        
        val tmpFile = Files.createTempFile(parentDir, "downloads", ".json.tmp")
        try {
            Files.writeString(tmpFile, json.encodeToString(records))
            try {
                Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmpFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Files.deleteIfExists(tmpFile)
            if (parentDir.toFile().exists()) throw e
        }
    }
}
