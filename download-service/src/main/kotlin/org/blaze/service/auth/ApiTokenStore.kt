package org.blaze.service.auth

import org.slf4j.LoggerFactory
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the bearer token that authenticates local API clients.
 *
 * The token is generated with [SecureRandom] (never a hardcoded secret), persisted to a file created
 * with owner-only `0600` permissions, and reused across restarts so trusted clients that already read it
 * keep working. It is deliberately kept in its own file, separate from the world-readable service
 * descriptor, so discovery never leaks the secret. [matches] uses a constant-time comparison so a caller
 * cannot probe the token byte-by-byte.
 */
class ApiTokenStore(private val tokenFile: Path) {
    private val logger = LoggerFactory.getLogger(ApiTokenStore::class.java)

    @Volatile
    private var cached: String? = null

    /** The current token: loaded from disk, or generated and written on first use. */
    fun token(): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val token = if (Files.exists(tokenFile)) {
                Files.readString(tokenFile).trim().takeIf { it.isNotBlank() } ?: generate()
            } else {
                generate()
            }
            persist(token)
            cached = token
            return token
        }
    }

    /** True when [presented] equals the active token, in constant time. */
    fun matches(presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        val expected = token()
        if (expected.length != presented.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor presented[i].code)
        return diff == 0
    }

    /** Replaces the active token (used by an explicit rotate); returns the new value. */
    fun rotate(): String {
        val fresh = generate()
        persist(fresh)
        cached = fresh
        return fresh
    }

    private fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun persist(token: String) {
        runCatching {
            Files.createDirectories(tokenFile.toAbsolutePath().parent)
            Files.writeString(tokenFile, token)
            restrictToOwner()
        }.onFailure { logger.error("Could not persist the local API token", it) }
    }

    private fun restrictToOwner() {
        runCatching {
            val perms = PosixFilePermissions.fromString("rw-------")
            Files.setPosixFilePermissions(tokenFile, perms)
        } // Non-POSIX filesystems (Windows) fall back to their ACL defaults.
    }
}
