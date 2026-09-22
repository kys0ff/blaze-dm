package org.blaze.service.instance

import kotlinx.serialization.json.Json
import org.blaze.download.api.ServiceDescriptor
import org.blaze.download.api.ServicePaths
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Publishes and reads the [ServiceDescriptor] so clients can find the running service.
 *
 * The descriptor holds only non-secret routing data (host, port, pid, token-file path); the bearer token
 * itself lives in the separate `0600` file it points at. [clear] removes the endpoint file on clean
 * shutdown so a later client does not connect to a dead port.
 */
class ServiceDiscovery(private val storageDir: Path) {
    private val logger = LoggerFactory.getLogger(ServiceDiscovery::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val endpointFile: Path = ServicePaths.endpointFileFor(storageDir)

    fun publish(descriptor: ServiceDescriptor) {
        runCatching {
            Files.createDirectories(storageDir)
            Files.writeString(endpointFile, json.encodeToString(ServiceDescriptor.serializer(), descriptor))
        }.onFailure { logger.error("Failed to publish the service descriptor", it) }
    }

    fun read(): ServiceDescriptor? {
        if (!Files.exists(endpointFile)) return null
        return runCatching { json.decodeFromString<ServiceDescriptor>(Files.readString(endpointFile)) }
            .onFailure { logger.warn("Corrupt service descriptor at {}", endpointFile, it) }
            .getOrNull()
    }

    fun clear() {
        runCatching { Files.deleteIfExists(endpointFile) }
    }
}
