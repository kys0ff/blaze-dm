package org.blaze.osintegration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A startup-safe buffer of external-open requests.
 *
 * Every producer - argv at cold start, macOS open events arriving mid-launch, IPC forwards from a
 * second launcher process - calls [submit]; whatever consumer exists later (the GUI dialog queue, a
 * headless router) observes [pending] as state, so a request can never be dropped because "the UI was
 * not ready yet". Consumption is explicit: the GUI [ack]nowledges a request once it has been surfaced
 * to the user, which removes it from the queue.
 *
 * Identical requests already sitting in the queue are collapsed (dedup by equality): double-activation
 * of a file association must not queue the same torrent twice, but the same magnet opened *after* its
 * download finished is still a fresh request.
 */
class ExternalOpenInbox {

    private val _pending = MutableStateFlow<List<ExternalOpenRequest>>(emptyList())

    /** The queue as observable state; consumers see requests in arrival order. */
    val pending: StateFlow<List<ExternalOpenRequest>> = _pending.asStateFlow()

    private val _bringToFront = MutableStateFlow(0L)

    /**
     * Bumped whenever the OS asks the running instance to surface itself (macOS Dock reopen today;
     * any future "activate" event tomorrow). Listeners raise the window; requests stay queued so
     * "show window" and "open this torrent" remain one atomic user-visible outcome.
     */
    val bringToFrontSignal: StateFlow<Long> = _bringToFront.asStateFlow()

    fun notifyBringToFront() {
        _bringToFront.getAndUpdate { it + 1 }
    }

    fun submit(request: ExternalOpenRequest) = submit(listOf(request))

    fun submit(requests: Collection<ExternalOpenRequest>) {
        if (requests.isEmpty()) return
        _pending.getAndUpdate { current ->
            current + requests.filter { it !in current }
        }
    }

    /** Removes the first occurrence of [request]; call once it has been surfaced/handled. */
    fun ack(request: ExternalOpenRequest) {
        _pending.getAndUpdate { current ->
            val index = current.indexOf(request)
            if (index < 0) current else current - current[index]
        }
    }

    fun clear() {
        _pending.value = emptyList()
    }
}
