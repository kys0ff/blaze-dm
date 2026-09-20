package org.blaze.presentation.components.notifications

import androidx.compose.runtime.Stable

@Stable
class Notification internal constructor(
    val id: Long,
    val type: NotificationType,
    val message: String,
    val title: String?,
)