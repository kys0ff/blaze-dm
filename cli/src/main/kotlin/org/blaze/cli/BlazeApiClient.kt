package org.blaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.blaze.download.api.ApiCapabilities
import org.blaze.download.api.DownloadDto
import org.blaze.download.api.ServiceDescriptor
import org.blaze.download.api.ServicePaths
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Discovers and calls the running Blaze downloader over its loopback HTTP API.
 *
 * It is a pure protocol client: it depends only on the public API types (for parsing) and the JDK HTTP
 * client. Endpoint + token come from, in priority order, explicit CLI flags, then `BLAZE_API_URL` /
 * `BLAZE_API_TOKEN`, then the on-disk service descriptor the running service publishes (plus the
 * owner-only token file it points at). It never constructs or embeds an engine - if no service answers,
 * it raises [CliError] with [ExitCodes.SERVICE_UNAVAILABLE] rather than silently starting a second one.
 */
class BlazeApiClient(
    private val endpoint: Endpoint,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Endpoint(val baseUrl: String, val token: String?)

    data class Response(val status: Int, val body: String)

    class CliError(val exitCode: Int, message: String) : RuntimeException(message)

    // ---- discovery ----------------------------------------------------------------------------

    companion object {
        fun discover(storageDir: Path, urlOverride: String?, tokenOverride: String?): Endpoint {
            urlOverride?.let { url ->
                return Endpoint(url.trimEnd('/'), tokenOverride ?: readEnvToken())
            }
            val envUrl = System.getenv("BLAZE_API_URL")
            val descriptor = readDescriptor(storageDir)
            val baseUrl = urlOverride ?: envUrl?.trimEnd('/') ?: descriptor?.let { "http://${it.host}:${it.port}" }
            ?: throw CliError(
                ExitCodes.SERVICE_UNAVAILABLE,
                "No Blaze service is running for $storageDir. Start the GUI or `blaze serve`, or pass --url.",
            )
            val token = tokenOverride ?: System.getenv("BLAZE_API_TOKEN")
                ?: descriptor?.let { readToken(Path.of(it.tokenFile)) }
            return Endpoint(baseUrl, token)
        }

        private fun readDescriptor(storageDir: Path): ServiceDescriptor? {
            val file = ServicePaths.endpointFileFor(storageDir)
            if (!Files.exists(file)) return null
            return runCatching {
                Json.decodeFromString(ServiceDescriptor.serializer(), Files.readString(file))
            }.getOrNull()
        }

        private fun readToken(tokenFile: Path): String? =
            runCatching { Files.readString(tokenFile).trim() }.getOrNull()?.takeIf { it.isNotBlank() }

        private fun readEnvToken(): String? = System.getenv("BLAZE_API_TOKEN")?.takeIf { it.isNotBlank() }
    }

    // ---- calls --------------------------------------------------------------------------------

    fun health(): Response = get("/api/v1/health", auth = false)
    fun capabilities(): Response = get("/api/v1/capabilities", auth = false)
    fun list(): Response = get("/api/v1/downloads")
    fun info(id: String): Response = get("/api/v1/downloads/$id")

    fun action(id: String, verb: String): Response =
        post("/api/v1/downloads/${id}/${verb}", body = null)

    fun remove(id: String, deleteFiles: Boolean): Response =
        delete("/api/v1/downloads/$id${if (deleteFiles) "?deleteFiles=true" else ""}")

    fun create(
        sourceKey: String,
        sourceValue: String,
        destination: String,
        filename: String?,
        connections: Int?,
        bandwidth: Long?,
        autoStart: Boolean,
        referer: String?,
        userAgent: String?,
        credentials: Map<String, String> = emptyMap(),
    ): Response {
        val body = buildJsonObject {
            put(sourceKey, sourceValue)
            put("destination", destination)
            filename?.let { put("filename", it) }
            connections?.let { put("connections", it) }
            bandwidth?.let { put("bandwidthLimitBytesPerSecond", it) }
            put("autoStart", autoStart)
            referer?.let { put("referer", it) }
            userAgent?.let { put("userAgent", it) }
            // Ephemeral auth material: sent for this transfer only, never echoed back or stored.
            if (credentials.isNotEmpty()) {
                put(
                    "credentials",
                    buildJsonObject { credentials.forEach { (k, v) -> put(k, v) } },
                )
            }
        }
        return post("/api/v1/downloads", body = body.toString())
    }

    /** Runs [block] for each SSE `data:` event until it returns true (stop) or the stream ends. */
    fun streamEvents(stopWhen: (JsonObject) -> Boolean) {
        val uri = URI.create("${endpoint.baseUrl}/api/v1/events")
        val builder = HttpRequest.newBuilder(uri)
            .header("Accept", "text/event-stream")
            .timeout(null as Duration?)
        addAuth(builder)
        val resp = runCatching {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofLines())
        }.getOrElse { throw CliError(ExitCodes.SERVICE_UNAVAILABLE, "Cannot reach the Blaze service: ${it.message}") }
        checkAuth(resp.statusCode(), Response(resp.statusCode(), ""))
        resp.body().use { lines ->
            for (line in lines.iterator()) {
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                if (stopWhen(obj)) break
            }
        }
    }

    // ---- decoding -----------------------------------------------------------------------------

    fun decodeList(body: String): List<DownloadDto> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DownloadDto.serializer()), body)

    fun decodeDto(body: String): DownloadDto = json.decodeFromString(DownloadDto.serializer(), body)

    fun decodeCapabilities(body: String): ApiCapabilities = json.decodeFromString(ApiCapabilities.serializer(), body)

    // ---- transport plumbing -------------------------------------------------------------------

    private fun get(path: String, auth: Boolean = true): Response {
        val builder = HttpRequest.newBuilder(URI.create(endpoint.baseUrl + path))
            .timeout(Duration.ofSeconds(15))
            .GET()
        if (auth) addAuth(builder)
        return execute(builder.build())
    }

    private fun post(path: String, body: String?): Response {
        val builder = HttpRequest.newBuilder(URI.create(endpoint.baseUrl + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
        addAuth(builder)
        val publisher = body?.let {
            HttpRequest.BodyPublishers.ofString(it, StandardCharsets.UTF_8)
        } ?: HttpRequest.BodyPublishers.noBody()
        return execute(builder.POST(publisher).build())
    }

    private fun delete(path: String): Response {
        val builder = HttpRequest.newBuilder(URI.create(endpoint.baseUrl + path))
            .timeout(Duration.ofSeconds(15))
        addAuth(builder)
        return execute(builder.DELETE().build())
    }

    private fun execute(request: HttpRequest): Response {
        val resp = runCatching {
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }.getOrElse {
            throw CliError(ExitCodes.SERVICE_UNAVAILABLE, "Cannot reach the Blaze service: ${it.message}")
        }
        checkAuth(resp.statusCode(), Response(resp.statusCode(), resp.body()))
        return Response(resp.statusCode(), resp.body())
    }

    private fun addAuth(builder: HttpRequest.Builder) {
        endpoint.token?.let { builder.header("Authorization", "Bearer $it") }
    }

    /** Turns API error statuses into meaningful exit codes; successful statuses are a no-op. */
    private fun checkAuth(status: Int, response: Response) {
        when (status) {
            401, 403 -> {
                val msg = runCatching {
                    Json.parseToJsonElement(response.body).jsonObject["error"]?.jsonObject
                        ?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                throw CliError(
                    if (status == 401) ExitCodes.AUTH_FAILURE else ExitCodes.GENERAL_FAILURE,
                    msg ?: if (status == 401) "Authentication failed - provide a valid token" else "Access denied",
                )
            }
        }
    }
}
