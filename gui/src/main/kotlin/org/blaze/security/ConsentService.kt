package org.blaze.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.blaze.data.AppSettingsRepository
import org.blaze.data.RememberedClient
import org.blaze.data.TrustedExtension
import org.blaze.service.security.ClientConsentGate
import org.blaze.service.security.ClientIdentity
import org.blaze.service.security.ConsentDecision
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the user is being asked to approve. */
enum class ConsentSubjectKind {
    /** A third-party program driving the local download API. */
    EXTERNAL_CLIENT,

    /** A link-handler plugin jar. */
    LINK_RESOLVER,

    /** A color-theme plugin jar. */
    THEME,
}

/**
 * One unanswered approval sitting in front of the user.
 *
 * The data layer only ever describes the subject - [subjectName] is the client's self-declared
 * display name or the jar's file name, [subjectDetail] the stable id / content hash - and the dialog
 * composes the "here is what accepting means" copy around it. A request is answered through
 * [ConsentService.respond]; closing the dialog unanswered counts as a rejection.
 */
data class ConsentRequest(
    val id: Long,
    val kind: ConsentSubjectKind,
    val subjectName: String,
    val subjectDetail: String,
)

/**
 * The app's consent layer: turns "somebody external wants in" into a user decision.
 *
 * It implements the transport's [ClientConsentGate] (a third-party client connecting over the local
 * API) and additionally guards extension jars (link resolvers and themes), because both let outside
 * code act inside Blaze - one through its download list, the other by literally running in-process.
 * Every prompt honors three answers: *allow once* (this run only, kept in memory), *allow always*
 * (persisted to [AppSettingsRepository] as a [RememberedClient]/[TrustedExtension], revocable in
 * Settings) and *reject*. The per-feature switches (`approveExternalClients` / `approveExtensions`)
 * are the settings override: switching one off restores the "token is enough / drop the jar and it
 * loads" behavior for that surface.
 *
 * A one-time approval deliberately does *not* skip later prompts after a restart, and a jar dropped
 * into an extensions folder is never loaded before its approval - the registries' trust filters read
 * exactly this class, so "not answered yet" means "code does not run".
 */
class ConsentService(
    private val appSettingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope,
) : ClientConsentGate {
    private val logger = LoggerFactory.getLogger(ConsentService::class.java)
    private val nextId = AtomicLong()

    private val _pending = MutableStateFlow<List<ConsentRequest>>(emptyList())
    /** The requests the consent dialog should be showing, oldest first. */
    val pending: StateFlow<List<ConsentRequest>> = _pending.asStateFlow()

    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<ConsentDecision>>()

    /** Approvals given "just this time": alive for the process, gone on restart. */
    private val runScopedClients = ConcurrentHashMap<String, Boolean>()
    private val runScopedExtensions = ConcurrentHashMap<String, Boolean>()

    /** sha256 per jar, memoized while the file's size+mtime are unchanged (reload is not rare but hashing is IO). */
    private data class HashCache(val size: Long, val mtime: Long, val hash: String)
    private val hashCache = ConcurrentHashMap<Path, HashCache>()

    // ---- local API clients --------------------------------------------------------------------

    /**
     * The transport asks this before a never-seen client's first request proceeds. Auto-approved when
     * the prompt switch is off or the client is already remembered; otherwise the caller suspends (and
     * with it the client's request) until the dialog is answered.
     */
    override suspend fun decide(client: ClientIdentity): ConsentDecision {
        val settings = appSettingsRepository.settings.value
        if (!settings.approveExternalClients) return ConsentDecision.ALLOW_ALWAYS
        if (settings.rememberedClients.any { it.id == client.id }) return ConsentDecision.ALLOW_ALWAYS
        if (runScopedClients.containsKey(client.id)) return ConsentDecision.ALLOW_ONCE

        val decision = prompt(
            ConsentRequest(nextId.getAndIncrement(), ConsentSubjectKind.EXTERNAL_CLIENT, client.displayName, client.id)
        )
        when (decision) {
            ConsentDecision.ALLOW_ONCE -> runScopedClients[client.id] = true
            ConsentDecision.ALLOW_ALWAYS -> appSettingsRepository.updateSettings {
                it.copy(
                    rememberedClients = it.rememberedClients.filterNot { c -> c.id == client.id } +
                        RememberedClient(client.id, client.displayName, System.currentTimeMillis())
                )
            }
            ConsentDecision.DENY -> Unit
        }
        return decision
    }

    // ---- extension jars -----------------------------------------------------------------------

    /**
     * Synchronous trust check behind the plugin loaders' filter: trusted = the switch is off, the jar's
     * bytes were always-approved, or the user already allowed it this run. Never prompts - an untrusted
     * jar is skipped and surfaces through [requestExtensionReview].
     */
    fun isExtensionTrusted(kind: ConsentSubjectKind, jar: Path): Boolean {
        val settings = appSettingsRepository.settings.value
        if (!settings.approveExtensions) return true
        val hash = contentHash(jar)
        if (settings.trustedExtensions.any { it.sha256 == hash && it.kind == kind.name }) return true
        return runScopedExtensions.containsKey(hashKey(kind, hash))
    }

    /**
     * The install-time gate: asks (unless exempt) and maps the answer onto whether the install may
     * proceed. A one-time approval is recorded so the immediately-following reload does not ask twice
     * for the same jar.
     */
    suspend fun awaitExtensionApproval(kind: ConsentSubjectKind, jar: Path): ConsentDecision {
        val settings = appSettingsRepository.settings.value
        if (!settings.approveExtensions) return ConsentDecision.ALLOW_ALWAYS
        val hash = contentHash(jar)
        if (settings.trustedExtensions.any { it.sha256 == hash && it.kind == kind.name }) return ConsentDecision.ALLOW_ALWAYS
        if (runScopedExtensions.containsKey(hashKey(kind, hash))) return ConsentDecision.ALLOW_ONCE

        val decision = prompt(
            ConsentRequest(
                nextId.getAndIncrement(),
                kind,
                jar.fileName?.toString() ?: jar.toString(),
                hash,
            )
        )
        when (decision) {
            ConsentDecision.ALLOW_ONCE -> runScopedExtensions[hashKey(kind, hash)] = true
            ConsentDecision.ALLOW_ALWAYS -> appSettingsRepository.updateSettings {
                it.copy(
                    trustedExtensions = it.trustedExtensions.filterNot { e -> e.sha256 == hash && e.kind == kind.name } +
                        TrustedExtension(hash, jar.fileName?.toString().orEmpty(), kind.name, System.currentTimeMillis())
                )
            }
            ConsentDecision.DENY -> Unit
        }
        return decision
    }

    /**
     * Fire-and-forget review of jars a reload skipped (dropped into an extensions folder by hand).
     * Each is asked in turn; as soon as one is approved, [onApproved] runs (a registry reload) so the
     * freshly trusted jar actually gets loaded. Rejections simply leave the jar inert.
     */
    fun requestExtensionReview(kind: ConsentSubjectKind, jars: List<Path>, onApproved: () -> Unit) {
        scope.launch {
            var approvedAny = false
            for (jar in jars) {
                val decision = runCatching { awaitExtensionApproval(kind, jar) }
                    .onFailure { logger.warn("Extension review for {} did not finish", jar, it) }
                    .getOrDefault(ConsentDecision.DENY)
                if (decision != ConsentDecision.DENY) approvedAny = true
            }
            if (approvedAny) runCatching(onApproved)
                .onFailure { logger.error("Reload after extension approval failed", it) }
        }
    }

    // ---- dialog plumbing ----------------------------------------------------------------------

    private suspend fun prompt(request: ConsentRequest): ConsentDecision {
        // One live prompt per subject: racing requests for the same client/jar share the first dialog.
        _pending.value.firstOrNull { it.kind == request.kind && it.subjectDetail == request.subjectDetail }?.let { duplicate ->
            return waiters[duplicate.id]?.await() ?: ConsentDecision.DENY
        }
        val waiter = CompletableDeferred<ConsentDecision>()
        waiters[request.id] = waiter
        _pending.update { it + request }
        logger.info("Consent requested from the user: {} '{}' ({})", request.kind, request.subjectName, request.subjectDetail)
        try {
            return waiter.await()
        } catch (e: Exception) {
            // Nobody answered before this coroutine died: refuse rather than let the caller through.
            waiter.complete(ConsentDecision.DENY)
            throw e
        } finally {
            _pending.update { it.filterNot { r -> r.id == request.id } }
            waiters.remove(request.id)
        }
    }

    /** The dialog's button handler. Unknown ids (already answered/withdrawn) are ignored. */
    fun respond(requestId: Long, decision: ConsentDecision) {
        waiters[requestId]?.complete(decision)
    }

    /** Withdraws a request whose dialog was closed without an answer: silence means refusal. */
    fun rejectUnanswered(requestId: Long) = respond(requestId, ConsentDecision.DENY)

    private fun hashKey(kind: ConsentSubjectKind, hash: String): String = "${kind.name}:$hash"

    /** SHA-256 (hex) of the jar's bytes; a read failure hashes to "unknown", which is never trusted. */
    fun contentHash(jar: Path): String =
        runCatching {
            val size = Files.size(jar)
            val mtime = Files.getLastModifiedTime(jar).toMillis()
            val cached = hashCache[jar]
            if (cached != null && cached.size == size && cached.mtime == mtime) return cached.hash
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(jar).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            hashCache[jar] = HashCache(size, mtime, hex)
            hex
        }.getOrElse {
            logger.warn("Could not hash extension jar {}", jar, it)
            "unreadable:" + jar.fileName
        }
}
