package org.blaze.service.transport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.blaze.download.api.*
import org.blaze.service.auth.ApiTokenStore
import org.blaze.service.config.LocalApiSettings
import org.blaze.service.mapping.toApiError
import org.blaze.service.security.ClientConsentGate
import org.blaze.service.security.ClientIdentity
import org.blaze.service.security.ConsentDecision
import org.blaze.service.security.PathValidator
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * The loopback HTTP transport that exposes the public [DownloadsApi] to local clients.
 *
 * It is a thin adapter: parse a request → call [DownloadsApi] → serialize the DTO/event or the `{ "error":
 * {...} }` envelope. It never touches the engine and never sits in the byte-transfer path. Security is
 * enforced right here, at the trust boundary, before any call reaches the domain:
 *
 *  - **loopback-only**: the socket binds [LocalApiSettings.host] (default 127.0.0.1) and the `Host` header
 *    is validated to reject DNS-rebinding, so a page resolving `evil.com -> 127.0.0.1` cannot drive it;
 *  - **bearer token**: every state-touching request must present the token (header or `?token=`), compared
 *    in constant time by [ApiTokenStore];
 *  - **explicit origins**: a cross-origin browser request is only answered for an allow-listed `Origin`;
 *    CORS is a *guardrail*, never the authentication itself (the token still has to be right);
 *  - **body cap + rate limit**: oversized bodies and abusive clients are rejected before parsing;
 *  - **confined destinations**: untrusted creates are limited to [LocalApiSettings.allowedDownloadRoots];
 *  - **user consent**: a client the person in front of the app has never approved is paused at the
 *    gate until they answer a consent prompt (allow once / allow always / reject), delegated to
 *    [ClientConsentGate] — see [approveClient].
 *
 * `GET /api/v1/health` and `GET /api/v1/capabilities` stay unauthenticated because they leak only liveness
 * and feature flags, which a client needs before it can even know to authenticate.
 */
class LocalApiServer(
    private val api: DownloadsApi,
    private val settings: LocalApiSettings,
    private val tokenStore: ApiTokenStore,
    private val scope: CoroutineScope,
    private val consentGate: ClientConsentGate = ClientConsentGate.ALLOW_ALL,
    private val destinationValidator: PathValidator =
        PathValidator(
            settings.allowedDownloadRoots.takeIf { it.isNotEmpty() }
                ?.map { Paths.get(it).toAbsolutePath().normalize() },
        ),
) {
    private val logger = LoggerFactory.getLogger(LocalApiServer::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var server: HttpServer? = null
    private val running = AtomicBoolean(false)
    private val activeStreams = ConcurrentHashMap.newKeySet<Job>()
    private val rate = SlidingWindowRateLimiter(windowMillis = 60_000, maxPerWindow = 240)

    /**
     * Clients the user has allowed *during this process*: both one-time and remembered approvals land
     * here, so a "just this time" answer costs the user one prompt per run, not one per request. A
     * remembered approval additionally lives in the consent store and survives a restart.
     */
    private val sessionApproved = ConcurrentHashMap<String, ConsentDecision>()

    /** One lock per client id so N racing first-requests raise one prompt, not N. */
    private val consentLocks = ConcurrentHashMap<String, Any>()

    val boundPort: Int get() = server?.address?.port ?: -1
    val boundHost: String get() = settings.host

    /** Starts the server; returns the actual port (useful when [LocalApiSettings.port] was 0). */
    fun start() {
        val srv = HttpServer.create(InetSocketAddress(boundHost, settings.port), 10)
        srv.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "blaze-api").apply { isDaemon = true }
        }
        srv.createContext("/", ::dispatch)
        srv.start()
        server = srv
        running.set(true)
        if (settings.apiLoggingEnabled) logger.info(
            "Local download API listening on http://{}:{}",
            boundHost,
            boundPort
        )
    }

    fun stop() {
        running.set(false)
        activeStreams.forEach { it.cancel() }
        server?.stop(0)
        server = null
    }

    private fun dispatch(exchange: HttpExchange) {
        try {
            handle(exchange)
        } catch (e: Throwable) {
            // Never leak a stack trace or class name to a caller.
            logger.warn("Unhandled error serving {} {}", exchange.requestMethod, exchange.requestURI.path, e)
            writeError(exchange, e.toApiError(), statusFor(e.toApiError().code))
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removeSuffix("/")
        val method = exchange.requestMethod.uppercase()

        if (!validateHost(exchange)) {
            return writeError(exchange, ApiErrorBody(ApiErrorCode.PERMISSION_DENIED, "invalid Host header"), 403)
        }

        // Browser preflight + cross-origin gate.
        if (!validateOrigin(exchange)) {
            return writeError(exchange, ApiErrorBody(ApiErrorCode.PERMISSION_DENIED, "origin not allowed"), 403)
        }
        if (method == "OPTIONS") {
            addCors(exchange)
            exchange.sendResponseHeaders(204, -1)
            return
        }

        if (!path.startsWith(ApiVersion.BASE_PATH)) {
            return writeError(
                exchange,
                ApiErrorBody(ApiErrorCode.INVALID_REQUEST, "unknown API path; expected ${ApiVersion.BASE_PATH}/*"),
                404,
            )
        }
        val rel = path.removePrefix(ApiVersion.BASE_PATH).ifEmpty { "/" }

        when {
            rel == "/health" && method == "GET" ->
                writeJson(exchange, 200, HealthResponse(ApiVersion.CURRENT, "ok"), HealthResponse.serializer())

            rel == "/capabilities" && method == "GET" ->
                writeJson(exchange, 200, api.capabilities, ApiCapabilities.serializer())

            rel == "/downloads" && method == "GET" -> withAuth(exchange) {
                writeJson(exchange, 200, api.snapshot(), ListSerializer(DownloadDto.serializer()))
            }

            rel == "/downloads" && method == "POST" -> withAuth(exchange) {
                enforceRate(exchange)
                val body = readBody(exchange)
                val req = try {
                    json.decodeFromString(CreateDownloadHttp.serializer(), body).toRequest()
                } catch (e: IOException) {
                    throw e // payload-too-large passthrough
                } catch (e: Exception) {
                    throw DownloadApiException(ApiErrorCode.INVALID_REQUEST, "malformed JSON body")
                }
                // Confinement is enforced here, at the untrusted boundary, so the in-process GUI caller is
                // never restricted while a browser/HTTP caller is held to the configured roots.
                destinationValidator.validateDirectory(req.destination)
                // Transport override header wins over a body field for idempotency.
                val keyed = req.idempotencyKey?.let { req } ?: exchange.requestHeaders.getFirst("Idempotency-Key")
                    ?.let { req.copy(idempotencyKey = it) } ?: req
                val created = runBlocking { api.create(keyed) }
                writeJson(exchange, 201, created, DownloadDto.serializer())
            }

            rel == "/events" && method == "GET" -> withAuth(exchange) { streamEvents(exchange) }

            rel.startsWith("/downloads/") -> handleById(
                exchange,
                method,
                rel.removePrefix("/downloads/").removePrefix("/")
            )

            else -> writeError(exchange, ApiErrorBody(ApiErrorCode.INVALID_REQUEST, "no such endpoint: $rel"), 404)
        }
    }

    private fun handleById(exchange: HttpExchange, method: String, tail: String) = withAuth(exchange) {
        val segments = tail.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            return@withAuth writeError(exchange, ApiErrorBody(ApiErrorCode.DOWNLOAD_NOT_FOUND, "missing id"), 404)
        }
        val id = segments[0]
        val action = segments.getOrNull(1)
        when (method) {
            "GET" if action == null -> {
                val dto = runBlocking { api.get(id) }
                    ?: return@withAuth writeError(
                        exchange,
                        ApiErrorBody(ApiErrorCode.DOWNLOAD_NOT_FOUND, "no such download"),
                        404
                    )
                writeJson(exchange, 200, dto, DownloadDto.serializer())
            }

            "DELETE" if action == null -> {
                val deleteFiles = exchange.requestURI.query?.contains("deleteFiles=true") == true
                runBlocking { api.remove(id, deleteFiles) }
                writeJson(exchange, 200, OkHttp(), OkHttp.serializer())
            }

            "POST" if action != null -> {
                enforceRate(exchange)
                runBlocking {
                    when (action) {
                        "start", "resume" -> api.resume(id)
                        "pause" -> api.pause(id)
                        "cancel" -> api.cancel(id)
                        "retry" -> api.retry(id)
                        else -> throw DownloadApiException(ApiErrorCode.INVALID_REQUEST, "unknown action: $action")
                    }
                }
                writeJson(exchange, 200, OkHttp(), OkHttp.serializer())
            }

            else -> writeError(exchange, ApiErrorBody(ApiErrorCode.INVALID_REQUEST, "unsupported method/path"), 405)
        }
    }

    // ---- security gates -----------------------------------------------------------------------

    private fun validateHost(exchange: HttpExchange): Boolean {
        val hostHeader = exchange.requestHeaders.getFirst("Host")?.substringBefore(':')?.trim()
        if (hostHeader.isNullOrEmpty()) return false
        val bare = hostHeader.removeSurrounding("[", "]")
        return bare == "localhost" || bare == "127.0.0.1" || bare == "::1" ||
                bare == boundHost || runCatching { InetAddress.getByName(bare).isLoopbackAddress }.getOrDefault(false)
    }

    private fun validateOrigin(exchange: HttpExchange): Boolean {
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return true // non-browser client
        return settings.allowedOrigins.any { it.equals(origin, ignoreCase = true) || it == "*" }
    }

    private inline fun withAuth(exchange: HttpExchange, block: () -> Unit) {
        if (settings.requireAuth) {
            val presented = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer")?.trim()
                ?: queryToken(exchange)
            if (!tokenStore.matches(presented)) {
                writeError(exchange, ApiErrorBody(ApiErrorCode.AUTH_REQUIRED, "a valid bearer token is required"), 401)
                return
            }
        }
        // The token proves the caller *may* connect; consent asks the user whether it *should*.
        if (!approveClient(exchange)) {
            writeError(
                exchange,
                ApiErrorBody(ApiErrorCode.PERMISSION_DENIED, "access to Blaze was not approved by the user"),
                403,
            )
            return
        }
        block()
    }

    /**
     * The human gate: before an unrecognized client reaches the domain, the consent gate is asked and
     * the request parks until the user answers (or the prompt times out, which is a refusal). Once a
     * client is approved it serves every request of the current process; an ALLOW_ALWAYS additionally
     * persists on the gate's side so future runs skip the prompt.
     *
     * The check runs after the token gate, so a random local process cannot spam the user's dialog:
     * only a caller that already holds the owner-readable token can trigger a prompt at all.
     */
    private fun approveClient(exchange: HttpExchange): Boolean {
        if (consentGate === ClientConsentGate.ALLOW_ALL) return true
        val client = clientIdentity(exchange)
        if (sessionApproved.containsKey(client.id)) return true
        // Serialize per id: racing first requests share one prompt instead of stacking several.
        val lock = consentLocks.computeIfAbsent(client.id) { Any() }
        synchronized(lock) {
            if (sessionApproved.containsKey(client.id)) return true
            val decision = try {
                runBlocking { withTimeoutOrNull(CONSENT_TIMEOUT_MS.milliseconds) { consentGate.decide(client) } }
            } catch (e: Exception) {
                logger.warn("Client consent for {} failed; treating as a refusal", client.id, e)
                null
            } ?: ConsentDecision.DENY
            if (decision != ConsentDecision.DENY) sessionApproved[client.id] = decision
            consentLocks.remove(client.id)
            logger.info(
                "Local API client '{}' ({}) was {} by the user.",
                client.displayName, client.id,
                when (decision) {
                    ConsentDecision.ALLOW_ONCE -> "allowed once"
                    ConsentDecision.ALLOW_ALWAYS -> "always allowed"
                    ConsentDecision.DENY -> "rejected"
                },
            )
            return decision != ConsentDecision.DENY
        }
    }

    /**
     * Derives who this request came from. A well-behaved client names itself through
     * [ApiHeaders.CLIENT_ID]/[ApiHeaders.CLIENT_NAME]; anything else (curl, a browser page, a hostile
     * caller) falls back to its user agent, then its address. Client-supplied strings are sanitized:
     * length-capped and control-character-free, because they are rendered in the user's dialog.
     */
    private fun clientIdentity(exchange: HttpExchange): ClientIdentity {
        val agent = sanitize(exchange.requestHeaders.getFirst("User-Agent"), max = 120)
        val id = sanitize(exchange.requestHeaders.getFirst(ApiHeaders.CLIENT_ID), max = 80)
            ?: ("agent:" + (agent ?: exchange.remoteAddress.address.hostAddress))
        val name = sanitize(exchange.requestHeaders.getFirst(ApiHeaders.CLIENT_NAME), max = 80)
            ?: agent
            ?: "Unknown client"
        return ClientIdentity(id, name, agent ?: "n/a")
    }

    private fun sanitize(raw: String?, max: Int): String? =
        raw?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

    private fun enforceRate(exchange: HttpExchange) {
        val key = exchange.remoteAddress.address.hostAddress
        if (!rate.tryAcquire(key)) {
            throw DownloadApiException(ApiErrorCode.RATE_LIMITED, "too many requests, slow down")
        }
    }

    private fun queryToken(exchange: HttpExchange): String? =
        exchange.requestURI.query?.split('&')
            ?.firstOrNull { it.startsWith("token=") }
            ?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8) }

    // ---- streaming ----------------------------------------------------------------------------

    private fun streamEvents(exchange: HttpExchange) {
        addCors(exchange)
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0) // chunked
        val out = exchange.responseBody

        val job = scope.launch {
            val channel = Channel<DownloadEvent>(Channel.BUFFERED)
            val collect = launch { api.events().collect { channel.trySend(it) } }
            try {
                while (isActive) {
                    val ev = withTimeoutOrNull(HEARTBEAT_MS.milliseconds) { channel.receiveCatching().getOrNull() }
                    val payload = if (ev != null) {
                        "data: ${json.encodeToString(DownloadEvent.serializer(), ev)}\n\n"
                    } else {
                        ": keep-alive\n\n"
                    }
                    try {
                        out.write(payload.toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                    } catch (_: IOException) {
                        break // client disconnected
                    }
                    if (!running.get()) break
                }
            } catch (_: IOException) {
                // socket gone
            } finally {
                collect.cancel()
                runCatching { out.close() }
                activeStreams.remove(coroutineContext[Job])
            }
        }
        activeStreams.add(job)
    }

    // ---- serialization helpers ----------------------------------------------------------------

    @kotlinx.serialization.Serializable
    private data class HealthResponse(val apiVersion: Int, val status: String)

    private fun readBody(exchange: HttpExchange): String {
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > settings.maxRequestBytes) {
            throw DownloadApiException(ApiErrorCode.PAYLOAD_TOO_LARGE, "request body exceeds the limit")
        }
        val bytes = exchange.requestBody.readNBytes((settings.maxRequestBytes + 1).toInt())
        if (bytes.size > settings.maxRequestBytes) {
            throw DownloadApiException(ApiErrorCode.PAYLOAD_TOO_LARGE, "request body exceeds the limit")
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun <T> writeJson(exchange: HttpExchange, status: Int, value: T, serializer: SerializationStrategy<T>) {
        val body = json.encodeToString(serializer, value)
        addCors(exchange)
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun writeError(exchange: HttpExchange, error: ApiErrorBody, status: Int) {
        runCatching { writeJson(exchange, status, ErrorEnvelope(error), ErrorEnvelope.serializer()) }
    }

    private fun addCors(exchange: HttpExchange) {
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return
        if (settings.allowedOrigins.any { it.equals(origin, true) || it == "*" }) {
            exchange.responseHeaders.add("Access-Control-Allow-Origin", origin)
            exchange.responseHeaders.add("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key")
            exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
            exchange.responseHeaders.add("Vary", "Origin")
        }
    }

    private fun statusFor(code: ApiErrorCode): Int = when (code) {
        ApiErrorCode.DOWNLOAD_NOT_FOUND -> 404
        ApiErrorCode.AUTH_REQUIRED -> 401
        ApiErrorCode.PERMISSION_DENIED -> 403
        ApiErrorCode.INVALID_REQUEST, ApiErrorCode.INVALID_URL, ApiErrorCode.INVALID_TORRENT,
        ApiErrorCode.UNSUPPORTED_SOURCE -> 400

        ApiErrorCode.CONFLICT, ApiErrorCode.ALREADY_EXISTS -> 409
        ApiErrorCode.RATE_LIMITED -> 429
        ApiErrorCode.PAYLOAD_TOO_LARGE -> 413
        ApiErrorCode.SERVICE_UNAVAILABLE -> 503
        ApiErrorCode.DESTINATION_UNAVAILABLE -> 507
        else -> 500
    }

    private companion object {
        const val HEARTBEAT_MS = 15_000L

        /** How long a request waits for the user to answer a consent prompt before it is refused. */
        const val CONSENT_TIMEOUT_MS = 5L * 60 * 1000
    }
}

/**
 * A fixed-window per-client request budget used to blunt token-guessing and abusive polling.
 *
 * It is intentionally simple: one counter per client address resets each window. It is a fairness /
 * abuse brake, not a QoS guarantee - the real gate is the bearer token.
 */
private class SlidingWindowRateLimiter(private val windowMillis: Long, private val maxPerWindow: Int) {
    private class Window(var startMs: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    fun tryAcquire(key: String): Boolean {
        val now = System.currentTimeMillis()
        val window = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.startMs >= windowMillis) Window(now, 0) else existing
        }!!
        synchronized(window) {
            if (window.count >= maxPerWindow) return false
            window.count++
            return true
        }
    }
}
