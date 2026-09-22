package org.blaze.service.mapping

import org.blaze.download.api.DownloadFile
import org.blaze.download.api.DownloadMetadataDto
import org.blaze.download.api.PortableInfoDto
import org.blaze.download.api.PortableKindDto
import org.blaze.engine.api.DownloadFileMetadata
import org.blaze.engine.api.DownloadMetadata
import org.blaze.engine.api.PortableDownloadInfo
import org.blaze.engine.api.PortableTransferKind

private fun DownloadFileMetadata.toFile(selected: Boolean = true) =
    DownloadFile(path = path, size = size, selected = selected)

fun DownloadMetadata.toDto(): DownloadMetadataDto = DownloadMetadataDto(
    name = name,
    totalBytes = totalSize,
    files = files?.map { it.toFile() },
)

fun PortableDownloadInfo.toDto(): PortableInfoDto = PortableInfoDto(
    kind = when (kind) {
        PortableTransferKind.HTTP -> PortableKindDto.HTTP
        PortableTransferKind.TORRENT -> PortableKindDto.TORRENT
    },
    suggestedName = suggestedName,
    source = source,
    totalBytes = totalBytes,
    availableBytes = availableBytes,
    resumable = resumable,
    multiFile = multiFile,
    files = files?.map { it.toFile() },
    mayRequireCredentials = mayRequireCredentials,
)
