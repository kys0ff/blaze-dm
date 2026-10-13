package org.blaze.service.mapping

import org.blaze.download.api.*
import org.blaze.engine.api.*

/**
 * Projects the engine's inspect-before-download results onto the transport DTOs.
 *
 * Kept as a tiny, total mapping so the service and every UI layer never touch the engine's internal HLS/ZIP
 * parse objects, and so a new engine verdict that has no DTO counterpart fails to compile rather than
 * silently collapsing into a wrong status. Nothing here carries credentials or signed URLs.
 */

fun HlsInspection.toDto(): HlsInspectionDto = HlsInspectionDto(
    status = status.toDto(),
    protection = protection.toDto(),
    variants = variants.map { it.toDto() },
    selectedVariantIndex = selectedVariantIndex,
    estimatedTotalBytes = estimatedTotalBytes,
    message = message,
)

fun HlsInspectStatus.toDto(): HlsInspectStatusDto = when (this) {
    HlsInspectStatus.Playable -> HlsInspectStatusDto.Playable
    HlsInspectStatus.NeedsDrmConsent -> HlsInspectStatusDto.NeedsDrmConsent
    HlsInspectStatus.LiveUnsupported -> HlsInspectStatusDto.LiveUnsupported
    HlsInspectStatus.NotAStream -> HlsInspectStatusDto.NotAStream
    HlsInspectStatus.Error -> HlsInspectStatusDto.Error
}

fun HlsProtectionLevel.toDto(): HlsProtectionDto = when (this) {
    HlsProtectionLevel.None -> HlsProtectionDto.None
    HlsProtectionLevel.OrdinaryEncryption -> HlsProtectionDto.OrdinaryEncryption
    HlsProtectionLevel.DrmSuspected -> HlsProtectionDto.DrmSuspected
    HlsProtectionLevel.Uncertain -> HlsProtectionDto.Uncertain
}

fun HlsVariantInfo.toDto(): HlsVariantDto = HlsVariantDto(
    index = index,
    bandwidth = bandwidth,
    resolution = resolution,
    codecs = codecs,
    language = language,
    name = name,
)

fun ArchiveInspection.toDto(): ArchiveInspectionDto = when (this) {
    is ArchiveInspection.Browsable -> ArchiveInspectionDto.Browsable(
        entries = entries.map { it.toDto() },
        totalUncompressedBytes = totalUncompressedBytes,
        selectiveSupported = selectiveSupported,
        reasonIfUnsupported = reasonIfUnsupported,
    )

    is ArchiveInspection.FullDownloadOnly -> ArchiveInspectionDto.FullDownloadOnly(reason)
    is ArchiveInspection.Unavailable -> ArchiveInspectionDto.Unavailable(reason)
}

fun ArchiveEntryInfo.toDto(): ArchiveEntryDto = ArchiveEntryDto(
    name = name,
    isDirectory = isDirectory,
    compressedSize = compressedSize,
    uncompressedSize = uncompressedSize,
    extractable = extractable,
)
