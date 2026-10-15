package org.blaze.download.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the *wire contract* of the public API.
 *
 * Field names and enum ordinals here are what a browser extension, a Python script or the CLI parse; a
 * rename is a breaking change. These tests pin the serialized shape (not Kotlin internals), so a
 * well-intentioned refactor that changes the JSON surface fails loudly.
 */
class DownloadApiContractTest {

    private val json = Json { encodeDefaults = true; prettyPrint = false }

    @Test
    fun `download dto serializes the documented field names`() {
        val dto = DownloadDto(
            id = "42",
            name = "ubuntu.iso",
            state = DownloadState.DOWNLOADING,
            source = SourceInfo(SourceKind.HTTPS, "https://example.com/ubuntu.iso"),
            destination = "/downloads/ubuntu.iso",
            totalBytes = 5_485_760_000,
            downloadedBytes = 4_509_715_660,
            speedBytesPerSecond = 75_916_902,
            etaSeconds = 12,
            createdAtEpochMs = 1_700_000_000_000,
        )
        val text = json.encodeToString(DownloadDto.serializer(), dto)

        assertTrue("\"id\":\"42\"" in text)
        assertTrue("\"state\":\"DOWNLOADING\"" in text)
        assertTrue("\"downloadedBytes\":4509715660" in text)
        assertTrue("\"totalBytes\":5485760000" in text)
        assertTrue("\"speedBytesPerSecond\":75916902" in text)
        assertTrue("\"etaSeconds\":12" in text)
        // The source is a safe locator only - no header/cookie field is ever present.
        assertTrue("\"locator\":\"https://example.com/ubuntu.iso\"" in text)
        assertFalse("Authorization" in text)
        assertFalse("cookie" in text.lowercase())
    }

    @Test
    fun `nullable progress and total are omitted-safe and round-trip`() {
        val dto = DownloadDto(
            id = "7",
            name = "stream",
            state = DownloadState.RESOLVING_METADATA,
            source = SourceInfo(SourceKind.MAGNET, "magnet:?xt=urn:btih:abc"),
            destination = "/d",
            createdAtEpochMs = 123,
        )
        val text = json.encodeToString(DownloadDto.serializer(), dto)
        val back = json.decodeFromString(DownloadDto.serializer(), text)
        assertNull(back.totalBytes)
        assertNull(back.progress)
        assertEquals(123L, back.createdAtEpochMs)
        assertEquals(SourceKind.MAGNET, back.source.kind)
    }

    @Test
    fun `error envelope matches the documented shape`() {
        val body = ApiErrorBody(ApiErrorCode.DOWNLOAD_NOT_FOUND, "Download does not exist", mapOf("id" to "9"))
        val text = json.encodeToString(ApiErrorBody.serializer(), body)
        assertTrue("\"code\":\"DOWNLOAD_NOT_FOUND\"" in text)
        assertTrue("\"message\":\"Download does not exist\"" in text)
        assertTrue("\"details\":{\"id\":\"9\"}" in text)
    }

    @Test
    fun `resume capability keeps all three of its states on the wire`() {
        // Clients warn about lost progress from this field, so `false` (proved refusal) and `null`
        // (never proved) must survive serialization as different values.
        val refused = baseDto().copy(sourceSupportsResume = false)
        assertTrue(
            "\"sourceSupportsResume\":false" in json.encodeToString(DownloadDto.serializer(), refused),
            "an explicit refusal must not be dropped as a default"
        )

        val proved = baseDto().copy(sourceSupportsResume = true)
        assertTrue("\"sourceSupportsResume\":true" in json.encodeToString(DownloadDto.serializer(), proved))

        val unproven = json.encodeToString(DownloadDto.serializer(), baseDto())
        assertTrue(
            "sourceSupportsResume" in unproven,
            "the field must be present so a client can tell 'unknown' from a missing older build"
        )
    }

    @Test
    fun `a payload from an older service still decodes with an unknown resume capability`() {
        val legacy = """
            {"id":"7","name":"stream","state":"QUEUED",
             "source":{"kind":"HTTPS","locator":"https://example.com/s","mayRequireCredentials":false},
             "destination":"/d","createdAtEpochMs":123}
        """.trimIndent()
        val back = json.decodeFromString(DownloadDto.serializer(), legacy)

        assertNull(back.sourceSupportsResume, "a response that never mentioned the field is unproven, not refusing")
    }

    @Test
    fun `probed metadata carries the same three-state resume answer`() {
        val text = json.encodeToString(DownloadMetadataDto.serializer(), DownloadMetadataDto(name = "a", resumeSupported = false))
        assertTrue("\"resumeSupported\":false" in text)
        assertNull(
            json.decodeFromString(DownloadMetadataDto.serializer(), """{"name":"a"}""").resumeSupported
        )
    }

    private fun baseDto() = DownloadDto(
        id = "7",
        name = "stream",
        state = DownloadState.QUEUED,
        source = SourceInfo(SourceKind.HTTPS, "https://example.com/s"),
        destination = "/d",
        createdAtEpochMs = 123,
    )

    @Test
    fun `state activity and terminality are explicit, not inferred`() {
        assertTrue(DownloadState.DOWNLOADING.isActive)
        assertTrue(DownloadState.QUEUED.isTerminal.not())
        assertTrue(DownloadState.COMPLETED.isTerminal)
        assertTrue(DownloadState.FAILED.isTerminal)
        assertTrue(DownloadState.CANCELLED.isTerminal)
        assertFalse(DownloadState.PAUSED.isActive)
        assertFalse(DownloadState.QUEUED.isActive)
        // The phases a client must keep polling for, and the reserved ones that still read as active.
        assertTrue(DownloadState.PROBING.isActive)
        assertTrue(DownloadState.MERGING.isActive)
        assertTrue(DownloadState.COMPLETING.isActive)
        assertFalse(DownloadState.PROBING.isTerminal)
        assertFalse(DownloadState.MERGING.isTerminal)
    }

    @Test
    fun `the new lifecycle states encode by name and round-trip`() {
        // Wire compatibility depends on the name, not the ordinal, so inserting PROBING/MERGING mid-enum
        // must not change how any value is (de)serialized.
        for (state in listOf(DownloadState.PROBING, DownloadState.MERGING)) {
            val text = json.encodeToString(DownloadDto.serializer(), baseDto().copy(state = state))
            assertTrue("\"state\":\"$state\"" in text, "must serialize as its name: $text")
            assertEquals(state, json.decodeFromString(DownloadDto.serializer(), text).state)
        }
    }

    @Test
    fun `new download request can describe every supported source`() {
        val text = json.encodeToString(
            NewDownloadRequest.serializer(),
            NewDownloadRequest(source = DownloadSource.Url("https://x/y"), destination = "/d"),
        )
        assertTrue("\"type\":\"url\"" in text || "\"url\":" in text)
    }

    @Test
    fun `version and base path are the stable v1 surface`() {
        assertEquals(1, ApiVersion.CURRENT)
        assertEquals("/api/v1", ApiVersion.BASE_PATH)
    }
}
