package org.blaze.osintegration.singleinstance

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/**
 * Owner-only shared secret for the single-instance IPC channel.
 *
 * Mirrors the local API's token discipline: a [SecureRandom] value written to a `0600` file inside the
 * data directory, verified with a constant-time comparison. It exists so that even the "local" transport
 * (a unix socket or a loopback port) authenticates its peer - filesystem reachability alone is never
 * treated as authorization.
 */
internal class InstanceTokenStore(private val tokenFile: Path) {
    private val logger = LoggerFactory.getLogger(InstanceTokenStore::class.java)

    @Volatile
    private var cached: String? = null

    /** The active token: freshly generated and persisted when this process is the owner. */
    fun token(): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val token = if (Files.exists(tokenFile)) {
                Files.readString(tokenFile).trim().takeIf { it.isNotBlank() } ?: generate()
            } else {
                generate()
            }
            runCatching {
                Files.createDirectories(tokenFile.toAbsolutePath().parent)
                Files.writeString(tokenFile, token)
                runCatching {
                    Files.setPosixFilePermissions(tokenFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
                } // Non-POSIX filesystems keep their ACL defaults.
            }.onFailure { logger.error("Could not persist the single-instance token", it) }
            cached = token
            return token
        }
    }

    /** Best-effort read used by a forwarding (non-owner) process; null when the owner is not published. */
    fun readPublished(): String? =
        runCatching { Files.readString(tokenFile).trim() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun matches(presented: String?): Boolean {
        val expected = cached ?: return false
        if (presented.isNullOrEmpty() || expected.length != presented.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor presented[i].code)
        return diff == 0
    }

    private fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
