package org.blaze.resolver

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.blaze.resolver.core.LinkResolverSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Persistence-focused tests for the resolver settings repository: the legacy `disabledHandlers`
 * list migrating into per-handler slices, the slice edits reaching disk, and the in-memory (Apply)
 * path being rewound by `revertToPersisted`.
 */
class LinkResolverSettingsRepositoryTest {

    private fun tempDir(): Path = Files.createTempDirectory("blaze-resolver-settings")

    private fun writeLegacyJson(dir: Path, body: String) {
        dir.resolve("resolvers.json").toFile().writeText(body)
    }

    @Test
    fun `legacy disabledHandlers list migrates into disabled slices`() {
        val dir = tempDir()
        writeLegacyJson(dir, """{"disabledHandlers": ["alpha", "beta"]}""")

        val settings = LinkResolverSettingsRepository(dir).settings.value

        assertFalse(settings.isEnabled("alpha"))
        assertFalse(settings.isEnabled("beta"))
        assertTrue(settings.isEnabled("gamma"))
        // The migration leaves the legacy list intact (still the canonical record) but backs it
        // with enabled = false slices, so both readers agree.
        assertEquals(setOf("alpha", "beta"), settings.disabledHandlerIds)
        assertFalse(settings.slice("alpha").enabled)
    }

    @Test
    fun `blank extensionDir falls back to the default extensions folder`() {
        val dir = tempDir()
        writeLegacyJson(dir, """{"alwaysAskHandler": false, "disabledHandlers": []}""")

        val settings = LinkResolverSettingsRepository(dir).settings.value

        assertEquals(false, settings.alwaysAskHandler)
        assertEquals(dir.resolve("extensions").toString(), settings.extensionDir)
    }

    @Test
    fun `disabling a handler persists to disk for a fresh reader`() = runTest {
        val dir = tempDir()
        val repo = LinkResolverSettingsRepository(dir)

        repo.setEnabled("alpha", enabled = false)

        val reloaded = LinkResolverSettingsRepository(dir).settings.value
        assertFalse(reloaded.isEnabled("alpha"))
        assertFalse(reloaded.slice("alpha").enabled)
    }

    @Test
    fun `option value and priority edits round-trip through the slice`() = runTest {
        val dir = tempDir()
        val repo = LinkResolverSettingsRepository(dir)

        repo.setOptionValue("alpha", "token", "secret")
        repo.setPriority("alpha", 7)

        val slice = repo.settings.value.slice("alpha")
        assertEquals("secret", slice.optionValues["token"])
        assertEquals(7, slice.priority)

        val reloaded = LinkResolverSettingsRepository(dir).settings.value.slice("alpha")
        assertEquals("secret", reloaded.optionValues["token"])
        assertEquals(7, reloaded.priority)
    }

    @Test
    fun `an in-memory edit is discarded by revertToPersisted`() = runTest {
        val dir = tempDir()
        val repo = LinkResolverSettingsRepository(dir)

        repo.setEnabled("alpha", enabled = false, persist = false)
        assertFalse(repo.settings.value.isEnabled("alpha"))

        repo.revertToPersisted()
        // Nothing was written to disk, so reverting restores the handler to enabled.
        assertTrue(repo.settings.value.isEnabled("alpha"))
    }
}
