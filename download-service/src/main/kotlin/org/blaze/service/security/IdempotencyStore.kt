package org.blaze.service.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers recent idempotency keys so a client that retries a `create` after a timeout gets the *same*
 * download back instead of a duplicate.
 *
 * Entries are keyed by the caller-supplied idempotency string and mapped to the download id that was
 * created for it. The store is bounded ([maxEntries]) and pruned by insertion order, so a runaway client
 * cannot grow it without limit. It is in-memory only on purpose: idempotency is about absorbing bursts of
 * retried submissions against a live service, not about deduplicating across process restarts.
 */
class IdempotencyStore(private val maxEntries: Int = 1024) {
    private val map = ConcurrentHashMap<String, String>()

    /** Returns the download id previously created for [key], or null. */
    fun lookup(key: String): String? = map[key]

    fun record(key: String, downloadId: String) {
        if (map.size >= maxEntries) prune()
        map[key] = downloadId
    }

    private fun prune() {
        // ConcurrentHashMap has no order; drop an arbitrary slice to keep the bound soft.
        val it = map.entries.iterator()
        var toDrop = maxEntries / 4
        while (it.hasNext() && toDrop > 0) {
            it.next()
            it.remove()
            toDrop--
        }
    }
}
