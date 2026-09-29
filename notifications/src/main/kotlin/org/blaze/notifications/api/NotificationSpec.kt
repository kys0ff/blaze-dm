package org.blaze.notifications.api

/**
 * A single notification request: the localized text plus how loudly it should land.
 *
 * The library deliberately knows nothing about *when* a notification belongs to the
 * user's attention - the embedding app decides that (see
 * [org.blaze.notifications.api.NotificationPublisher.gate]) and owns all wording,
 * so contents stay translatable and this type stays plain data.
 */
data class NotificationSpec(
    /** Bold first line, e.g. "Download completed". */
    val title: String,
    /** Detail line, e.g. the file name. Empty hides the body entirely. */
    val body: String = "",
    val urgency: NotificationUrgency = NotificationUrgency.NORMAL,
)

/**
 * How insistent the notification should be. Backends map this to their native level
 * (Freedesktop `urgency` hint, Windows balloon severity); platforms without a
 * severity concept are free to treat all values alike.
 */
enum class NotificationUrgency { LOW, NORMAL, CRITICAL }
