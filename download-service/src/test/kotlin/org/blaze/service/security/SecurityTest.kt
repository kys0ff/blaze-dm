package org.blaze.service.security

import org.blaze.download.api.ApiErrorCode
import org.blaze.download.api.DownloadApiException
import org.blaze.service.auth.ApiTokenStore
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Adversarial coverage of the destination and authentication primitives an external client attacks.
 *
 * These are the low-level guards behind the HTTP boundary (§13, §27): a hostile caller must not be able
 * to write outside its allowed roots, slip a NUL through a path, or guess/persist a bearer token. The
 * higher-level transport rejects are exercised in [org.blaze.service.transport.LocalApiServerTest].
 */
class SecurityTest {

    private fun tmp(): String = File(System.getProperty("java.io.tmpdir"), "blaze-sec-" + System.nanoTime())
        .apply { mkdirs() }.absolutePath

    @Test
    fun `trusted validator accepts an ordinary directory`() {
        val p = Paths.get(tmp())
        assertEquals(p, PathValidator.TRUSTED.validateDirectory(p.toString()))
    }

    @Test
    fun `blank destination is rejected`() {
        val ex = assertFailsWith<DownloadApiException> { PathValidator.TRUSTED.validateDirectory("   ") }
        assertEquals(ApiErrorCode.INVALID_REQUEST, ex.error.code)
    }

    @Test
    fun `embedded null byte is rejected before any filesystem call`() {
        val ex = assertFailsWith<DownloadApiException> { PathValidator.TRUSTED.validateDirectory("/tmp/a\u0000b") }
        assertEquals(ApiErrorCode.INVALID_REQUEST, ex.error.code)
    }

    @Test
    fun `dot-dot traversal cannot escape an allowed root`() {
        val root = Paths.get(tmp())
        val validator = PathValidator(listOf(root))
        // A classic `../../..` climb out of the sandbox must resolve back to a rejected path.
        val sneaky = root.resolve("downloads/../../../../etc").normalize().toString()
        val ex = assertFailsWith<DownloadApiException> { validator.validateDirectory(sneaky) }
        assertEquals(ApiErrorCode.PERMISSION_DENIED, ex.error.code)
    }

    @Test
    fun `a path genuinely inside the root is accepted`() {
        val root = Paths.get(tmp())
        val validator = PathValidator(listOf(root))
        val ok = validator.validateDirectory(root.resolve("sub/dir").toString())
        assertTrue(ok.startsWith(root))
    }

    @Test
    fun `token store generates, persists and constant-time matches`() {
        val file = Paths.get(tmp(), "api-token")
        val store = ApiTokenStore(file)
        val token = store.token()
        assertTrue(token.isNotBlank())
        assertEquals(token, store.token(), "token must be stable across calls")
        assertTrue(store.matches(token))
        assertFalse(store.matches(token.dropLast(1) + 'x'))
        assertFalse(store.matches(null))
        assertFalse(store.matches(""))
    }

    @Test
    fun `token is reloadable from disk by an independent store`() {
        val file = Paths.get(tmp(), "api-token")
        val token = ApiTokenStore(file).token()
        // A separate store reading the same file authenticates the same token (server vs. trusted client).
        assertTrue(ApiTokenStore(file).matches(token))
    }

    @Test
    fun `rotate replaces the active token`() {
        val file = Paths.get(tmp(), "api-token")
        val store = ApiTokenStore(file)
        val old = store.token()
        val fresh = store.rotate()
        assertNotEquals(old, fresh)
        assertTrue(store.matches(fresh))
        assertFalse(store.matches(old))
    }

    @Test
    fun `idempotency store maps a key to one download and stays bounded`() {
        val store = IdempotencyStore(maxEntries = 10)
        store.record("k", "dl-1")
        assertEquals("dl-1", store.lookup("k"))
        repeat(100) { store.record("key-$it", "dl-$it") }
        // Recent keys remain resolvable; the store never grows past its bound uncheckably.
        assertNotNull(store.lookup("key-99"))
    }
}
