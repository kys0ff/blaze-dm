package org.blaze.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.blaze.data.AppSettings
import org.blaze.data.AppSettingsRepository
import org.blaze.data.RememberedClient
import org.blaze.data.TrustedExtension
import org.blaze.service.security.ClientIdentity
import org.blaze.service.security.ConsentDecision
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour of the app's consent layer: the three answers (once / always / reject), the per-feature
 * override switches, and the remembered-approval persistence the Settings manager reads back.
 *
 * The gate suspends until a dialog is answered, so every prompting case runs [ConsentService.decide]
 * (or the extension variant) on a worker dispatcher while the test watches [ConsentService.pending]
 * and answers it - the same handshake the on-screen dialog performs. Auto-approve paths must return
 * without ever populating [ConsentService.pending], which is asserted by driving them under a timeout
 * that would hang if a prompt were raised.
 */
class ConsentServiceTest {

    private val tempDir = Files.createTempDirectory("blaze-consent-test")
    private val scope = CoroutineScope(Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempDir.toFile().deleteRecursively()
    }

    private fun service(settings: AppSettings = AppSettings()): Pair<ConsentService, AppSettingsRepository> {
        val repo = AppSettingsRepository(tempDir)
        repo.updateSettingsInMemory { settings }
        return ConsentService(repo, scope) to repo
    }

    private val client = ClientIdentity("client-1", "Blaze CLI", "curl/8")

    // ---- local API clients --------------------------------------------------------------------

    @Test
    fun `new client is prompted and honoring allow-once runs only this time`() = runBlocking {
        val (service, repo) = service()
        val deciding = async(Dispatchers.Default) { service.decide(client) }
        val request = withTimeout(2000) { service.pending.first { it.isNotEmpty() }.first() }
        assertEquals(ConsentSubjectKind.EXTERNAL_CLIENT, request.kind)
        service.respond(request.id, ConsentDecision.ALLOW_ONCE)

        assertEquals(ConsentDecision.ALLOW_ONCE, deciding.await())
        // Once-only is in-memory, never persisted for future runs.
        assertTrue(repo.settings.value.rememberedClients.isEmpty())

        // The same client is still allowed this run, but without raising another dialog.
        val again = withTimeout(2000) { service.decide(client) }
        assertEquals(ConsentDecision.ALLOW_ONCE, again)
        assertTrue(service.pending.value.isEmpty())
    }

    @Test
    fun `allow-always persists the client so later calls skip the prompt`() = runBlocking {
        val (service, repo) = service()
        val deciding = async(Dispatchers.Default) { service.decide(client) }
        val request = withTimeout(2000) { service.pending.first { it.isNotEmpty() }.first() }
        service.respond(request.id, ConsentDecision.ALLOW_ALWAYS)

        assertEquals(ConsentDecision.ALLOW_ALWAYS, deciding.await())
        assertTrue(repo.settings.value.rememberedClients.any { it.id == client.id })

        // A fresh service (simulating a restart) reads the persisted approval and does not ask.
        val restarted = ConsentService(repo, scope)
        val allowed = withTimeout(2000) { restarted.decide(client) }
        assertEquals(ConsentDecision.ALLOW_ALWAYS, allowed)
        assertTrue(restarted.pending.value.isEmpty())
    }

    @Test
    fun `reject is returned and not remembered`() = runBlocking {
        val (service, repo) = service()
        val deciding = async(Dispatchers.Default) { service.decide(client) }
        val request = withTimeout(2000) { service.pending.first { it.isNotEmpty() }.first() }
        service.respond(request.id, ConsentDecision.DENY)

        assertEquals(ConsentDecision.DENY, deciding.await())
        assertTrue(repo.settings.value.rememberedClients.isEmpty())
        // A refusal does not silently escalate: the client is asked again next time.
        val reDecide = async(Dispatchers.Default) { service.decide(client) }
        withTimeout(2000) { service.pending.first { it.isNotEmpty() }.first() }
        reDecide.cancel()
    }

    @Test
    fun `a remembered client is auto-approved without a dialog`() = runBlocking {
        val (service, repo) = service(
            AppSettings(rememberedClients = listOf(RememberedClient(client.id, client.displayName))),
        )
        val decision = withTimeout(2000) { service.decide(client) }
        assertEquals(ConsentDecision.ALLOW_ALWAYS, decision)
        assertTrue(service.pending.value.isEmpty())
        assertEquals(1, repo.settings.value.rememberedClients.size)
    }

    @Test
    fun `switching the prompt off lets any token holder through silently`() = runBlocking {
        val (service, _) = service(AppSettings(approveExternalClients = false))
        val decision = withTimeout(2000) { service.decide(client) }
        assertEquals(ConsentDecision.ALLOW_ALWAYS, decision)
        assertTrue(service.pending.value.isEmpty())
    }

    // ---- extension jars -----------------------------------------------------------------------

    @Test
    fun `untrusted extension is skipped and only trusted after allow-always`() = runBlocking {
        val jar = writeJar("resolver.jar")
        val (service, repo) = service()

        // The synchronous loader filter never prompts - an unseen jar is simply not trusted.
        assertFalse(service.isExtensionTrusted(ConsentSubjectKind.LINK_RESOLVER, jar))

        val deciding = async(Dispatchers.Default) { service.awaitExtensionApproval(ConsentSubjectKind.LINK_RESOLVER, jar) }
        val request = withTimeout(2000) { service.pending.first { it.isNotEmpty() }.first() }
        assertEquals(ConsentSubjectKind.LINK_RESOLVER, request.kind)
        service.respond(request.id, ConsentDecision.ALLOW_ALWAYS)
        assertEquals(ConsentDecision.ALLOW_ALWAYS, deciding.await())

        assertTrue(service.isExtensionTrusted(ConsentSubjectKind.LINK_RESOLVER, jar))
        // Trust is per-kind: approving a handler says nothing about a theme of the same bytes.
        assertFalse(service.isExtensionTrusted(ConsentSubjectKind.THEME, jar))
        assertTrue(repo.settings.value.trustedExtensions.any { it.kind == ConsentSubjectKind.LINK_RESOLVER.name })
    }

    @Test
    fun `already-trusted extension is approved without a dialog`() = runBlocking {
        val jar = writeJar("theme.jar")
        val (hasher, _) = service()
        val hash = hasher.contentHash(jar)
        val repo = repoWithTrusted(TrustedExtension(hash, "theme.jar", ConsentSubjectKind.THEME.name))
        val service = ConsentService(repo, scope)

        val decision = withTimeout(2000) { service.awaitExtensionApproval(ConsentSubjectKind.THEME, jar) }
        assertEquals(ConsentDecision.ALLOW_ALWAYS, decision)
        assertTrue(service.pending.value.isEmpty())
        assertTrue(service.isExtensionTrusted(ConsentSubjectKind.THEME, jar))
    }

    @Test
    fun `extension switch off loads any jar without asking`() = runBlocking {
        val jar = writeJar("free.jar")
        val (service, _) = service(AppSettings(approveExtensions = false))
        assertTrue(service.isExtensionTrusted(ConsentSubjectKind.LINK_RESOLVER, jar))
        val decision = withTimeout(2000) { service.awaitExtensionApproval(ConsentSubjectKind.LINK_RESOLVER, jar) }
        assertEquals(ConsentDecision.ALLOW_ALWAYS, decision)
        assertTrue(service.pending.value.isEmpty())
    }

    @Test
    fun `content hash is stable for identical bytes and differs for different ones`() {
        val (service, _) = service()
        val a = writeJar("a.jar", "same-payload")
        val aCopy = writeJar("a-copy.jar", "same-payload")
        val b = writeJar("b.jar", "different-payload")
        assertEquals(service.contentHash(a), service.contentHash(aCopy))
        assertFalse(service.contentHash(a) == service.contentHash(b))
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun writeJar(name: String, content: String = "PK\u0003\u0004 fake jar bytes"): java.nio.file.Path =
        tempDir.resolve(name).also { Files.write(it, content.toByteArray()) }

    private fun repoWithTrusted(extension: TrustedExtension): AppSettingsRepository {
        val repo = AppSettingsRepository(tempDir)
        repo.updateSettingsInMemory { it.copy(trustedExtensions = listOf(extension)) }
        return repo
    }
}
