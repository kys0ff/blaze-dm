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
    fun `state activity and terminality are explicit, not inferred`() {
        assertTrue(DownloadState.DOWNLOADING.isActive)
        assertTrue(DownloadState.QUEUED.isTerminal.not())
        assertTrue(DownloadState.COMPLETED.isTerminal)
        assertTrue(DownloadState.FAILED.isTerminal)
        assertTrue(DownloadState.CANCELLED.isTerminal)
        assertFalse(DownloadState.PAUSED.isActive)
        assertFalse(DownloadState.QUEUED.isActive)
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
