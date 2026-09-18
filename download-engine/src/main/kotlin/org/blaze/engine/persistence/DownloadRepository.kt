package org.blaze.engine.persistence

import kotlinx.serialization.json.Json
import java.nio.file.Path

class DownloadRepository(storageDir: Path) {
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
        dbFile.parentFile.mkdirs()
        dbFile.writeText(json.encodeToString(records))
    }
}
