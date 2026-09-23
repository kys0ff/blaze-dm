package org.blaze.download.api

import kotlinx.serialization.Serializable

/**
 * How urgent a download is relative to the others, part of the stable wire contract.
 *
 * The constants run from least to most urgent, so [ordinal] is a meaningful ranking a client can rely
 * on. What the engine actually does with it is described by [ApiCapabilities.prioritySupport]: a
 * strictly-FIFO build ignores it, while an [PrioritySupport.EXPLICIT] one reorders the queue and can
 * give a more urgent transfer a larger slice of the connection budget.
 */
@Serializable
enum class DownloadPriority {
    IDLE,
    LOW,
    NORMAL,
    HIGH;

    companion object {
        /** The priority a download gets when neither the client nor the engine default says otherwise. */
        val DEFAULT: DownloadPriority = NORMAL
    }
}
